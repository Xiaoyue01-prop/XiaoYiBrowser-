package com.example.webedge

import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebStorage
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.UUID

/**
 * Owns the ephemeral WebView profile for the current private-browsing session.
 * The profile name is intentionally held only in memory and is never persisted.
 */
class PrivateProfileManager {
    companion object {
        // ProfileStore is process-wide. All lifecycle calls run on the UI thread.
        private val liveProfiles = mutableSetOf<String>()
        private const val TAG = "PrivateProfile"
        private const val PROFILE_PREFIX = "summit_private_"
    }

    private var currentProfileName: String? = null
    private var sessionCookieManager: CookieManager? = null
    private var sessionWebStorage: WebStorage? = null

    val isSupported: Boolean
        get() = runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }
            .getOrDefault(false)

    /** Remove orphaned private profiles left behind if Android killed the process without onDestroy. */
    fun purgeOrphanedSessions() {
        if (!isSupported) return
        runCatching {
            val store = ProfileStore.getInstance()
            store.getAllProfileNames()
                .filter { it.startsWith(PROFILE_PREFIX) && it !in liveProfiles }
                .forEach { name ->
                    runCatching { store.deleteProfile(name) }
                        .onFailure { Log.w(TAG, "Không dọn được phiên riêng tư cũ: $name", it) }
                }
        }.onFailure { Log.w(TAG, "Không thể kiểm tra profile riêng tư cũ", it) }
    }

    private fun sessionName(): String {
        currentProfileName?.let { return it }
        return (PROFILE_PREFIX + UUID.randomUUID().toString().replace("-", "")).also {
            liveProfiles.add(it)
            currentProfileName = it
        }
    }

    /** Must be called immediately after WebView construction, before settings/clients/navigation. */
    fun applyProfile(view: WebView, tab: TabManager.Tab) {
        if (!tab.isPrivate) return
        check(isSupported) {
            "Phiên bản Android System WebView hiện tại chưa hỗ trợ Multi-Profile; không thể bảo đảm cách ly dữ liệu riêng tư."
        }
        WebViewCompat.setProfile(view, sessionName())
        // Cache profile-scoped stores while a WebView is available. This also lets the
        // Privacy Dashboard clear a private profile after its WebViews have been evicted.
        val profile = WebViewCompat.getProfile(view)
        sessionCookieManager = profile.cookieManager
        sessionWebStorage = profile.webStorage
    }

    /**
     * Makes a popup inherit the exact profile of its source WebView. This also supports
     * popup -> popup chains, where the source is not one of the TabManager-owned WebViews.
     * Must run immediately after popup construction, before reading settings or attaching it.
     */
    fun applyProfileFromSource(target: WebView, source: WebView) {
        if (!isSupported) {
            configureCookies(target, false)
            return
        }
        val sourceProfile = WebViewCompat.getProfile(source)
        val profileName = sourceProfile.name
        if (profileName != androidx.webkit.Profile.DEFAULT_PROFILE_NAME) {
            WebViewCompat.setProfile(target, profileName)
        }
        val isPrivateProfile = profileName.startsWith(PROFILE_PREFIX)
        if (isPrivateProfile) {
            target.isSaveEnabled = false
            target.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            sessionCookieManager = sourceProfile.cookieManager
            sessionWebStorage = sourceProfile.webStorage
        }
        sourceProfile.cookieManager.apply {
            setAcceptCookie(true)
            // Phiên riêng tư: popup cũng không nhận cookie bên thứ ba (cookie của chính popup vẫn là first-party).
            setAcceptThirdPartyCookies(target, !isPrivateProfile)
        }
    }

    /** True for tab WebViews and popup WebViews attached to the current ephemeral profile. */
    fun isPrivateProfile(view: WebView): Boolean =
        isSupported && runCatching {
            WebViewCompat.getProfile(view).name.startsWith(PROFILE_PREFIX)
        }.getOrDefault(false)

    /** CookieManager is profile-scoped; the platform singleton is used only for the normal profile. */
    fun cookieManagerFor(view: WebView, isPrivate: Boolean): CookieManager = if (isPrivate) {
        check(isSupported) { "Multi-Profile không được hỗ trợ" }
        sessionCookieManager ?: WebViewCompat.getProfile(view).cookieManager.also { sessionCookieManager = it }
    } else {
        CookieManager.getInstance()
    }

    fun webStorageFor(view: WebView, isPrivate: Boolean): WebStorage = if (isPrivate) {
        check(isSupported) { "Multi-Profile không được hỗ trợ" }
        sessionWebStorage ?: WebViewCompat.getProfile(view).webStorage.also { sessionWebStorage = it }
    } else {
        WebStorage.getInstance()
    }

    fun clearSessionData() {
        sessionCookieManager?.let { manager ->
            runCatching { manager.removeAllCookies(null); manager.flush() }
        }
        sessionWebStorage?.let { storage -> runCatching { storage.deleteAllData() } }
    }

    fun configureCookies(view: WebView, isPrivate: Boolean) {
        cookieManagerFor(view, isPrivate).apply {
            setAcceptCookie(true)
            // Tab riêng tư chặn cookie bên thứ ba để tracker xuyên site không bám theo; tab thường giữ nguyên.
            setAcceptThirdPartyCookies(view, !isPrivate)
        }
    }

    fun cookieHeader(view: WebView, isPrivate: Boolean, url: String): String? =
        cookieManagerFor(view, isPrivate).getCookie(url)

    /** Resolve the actual WebView profile; never fall back to normal cookies on a profile lookup failure. */
    fun cookieHeaderForProfile(view: WebView, url: String): String? =
        if (isSupported) WebViewCompat.getProfile(view).cookieManager.getCookie(url)
        else CookieManager.getInstance().getCookie(url)

    /** Call only after every WebView (including an authentication popup) has been destroyed. */
    fun endSession() {
        val name = currentProfileName ?: return
        liveProfiles.remove(name)
        currentProfileName = null
        sessionCookieManager = null
        sessionWebStorage = null
        if (!isSupported) return
        runCatching { ProfileStore.getInstance().deleteProfile(name) }
            .onFailure { Log.w(TAG, "Không xóa được profile riêng tư $name; sẽ thử dọn ở lần mở app sau", it) }
    }
}
