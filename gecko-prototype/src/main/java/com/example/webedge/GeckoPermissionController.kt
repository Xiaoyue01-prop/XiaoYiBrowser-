package com.example.webedge

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.Uri
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PermissionDelegate

/**
 * Quyền trang web cho Gecko (camera/micro/vị trí), dùng hộp thoại kính mờ của Summit.
 *
 * Nguyên tắc an toàn:
 * - Chỉ tab đang xem mới được hỏi; tab nền bị từ chối.
 * - Chỉ nguồn https (hoặc localhost) được cấp camera/micro/vị trí.
 * - Mọi loại quyền khác (thông báo, lưu trữ bền vững, DRM...) bị từ chối, giống khi chưa có delegate.
 * - Quyền Android chỉ nằm trong danh sách trắng và chỉ xin khi cần.
 * - "Chỉ lần này" không ghi vào Store.
 */
internal class GeckoPermissionController(
    private val activity: Activity,
    private val store: Store,
    private val dialog: () -> GlassDialogController,
    private val isActiveSession: (GeckoSession) -> Boolean,
    private val isPrivateSession: (GeckoSession) -> Boolean,
    private val isDestroyed: () -> Boolean
) : PermissionDelegate {

    companion object {
        private const val REQUEST_CODE = 7311
        private val LOCATION = setOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        private val ALLOWED_ANDROID = LOCATION + setOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }

    private class PendingAndroid(val permissions: List<String>, val done: (Boolean) -> Unit)

    private var pending: PendingAndroid? = null

    private fun has(permission: String): Boolean =
        activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Vị trí chỉ cần một trong hai (Android 12+ cho phép chỉ "gần đúng"); quyền khác phải đủ cả. */
    private fun satisfied(list: List<String>): Boolean {
        val location = list.filter { it in LOCATION }
        val others = list.filter { it !in LOCATION }
        return others.all(::has) && (location.isEmpty() || location.any(::has))
    }

    private fun requestAndroid(wanted: List<String>, done: (Boolean) -> Unit) {
        val list = wanted.distinct()
        if (list.isEmpty() || list.any { it !in ALLOWED_ANDROID }) { done(false); return }
        if (satisfied(list)) { done(true); return }
        if (pending != null || isDestroyed()) { done(false); return }
        pending = PendingAndroid(list, done)
        activity.requestPermissions(list.filterNot(::has).toTypedArray(), REQUEST_CODE)
    }

    /** Activity chuyển kết quả hộp thoại quyền của Android vào đây. */
    fun onRequestPermissionsResult(requestCode: Int) {
        if (requestCode != REQUEST_CODE) return
        val current = pending ?: return
        pending = null
        current.done(satisfied(current.permissions))
    }

    /** Gọi khi Activity bị hủy: trả lời mọi yêu cầu còn treo để trang không bị đơ. */
    fun cancelAll() {
        val current = pending
        pending = null
        current?.done?.invoke(false)
    }

    private fun parse(uri: String): Uri? = runCatching { Uri.parse(uri) }.getOrNull()

    private fun originKey(uri: String): String {
        val u = parse(uri) ?: return uri
        val host = u.host ?: return uri
        return "${u.scheme}://$host${if (u.port != -1) ":${u.port}" else ""}"
    }

    private fun isSecureOrigin(uri: String): Boolean {
        val u = parse(uri) ?: return false
        val host = u.host ?: return false
        return u.scheme.equals("https", true) || host == "localhost" || host == "127.0.0.1"
    }

    private fun displayHost(uri: String): String = parse(uri)?.host ?: uri

    override fun onAndroidPermissionsRequest(
        session: GeckoSession,
        permissions: Array<out String>?,
        callback: PermissionDelegate.Callback
    ) {
        activity.runOnUiThread {
            requestAndroid(permissions?.toList().orEmpty()) { ok ->
                if (ok) callback.grant() else callback.reject()
            }
        }
    }

    override fun onContentPermissionRequest(
        session: GeckoSession,
        perm: PermissionDelegate.ContentPermission
    ): GeckoResult<Int>? {
        if (perm.permission != PermissionDelegate.PERMISSION_GEOLOCATION) {
            return GeckoResult.fromValue(PermissionDelegate.ContentPermission.VALUE_DENY)
        }
        val result = GeckoResult<Int>()
        activity.runOnUiThread {
            val uri = perm.uri
            val allow = PermissionDelegate.ContentPermission.VALUE_ALLOW
            val deny = PermissionDelegate.ContentPermission.VALUE_DENY
            if (isDestroyed() || !isActiveSession(session) || !isSecureOrigin(uri)) {
                result.complete(deny)
                return@runOnUiThread
            }
            val origin = originKey(uri)
            // Capture privacy mode before showing an asynchronous dialog; the tab may close meanwhile.
            val privateRequest = isPrivateSession(session)
            // Private tabs never consume or persist per-origin permission decisions.
            val savedPermission = if (privateRequest) PermissionState.ASK
                else store.getGeoPermission(origin)
            when (savedPermission) {
                PermissionState.ALLOW -> result.complete(allow)
                PermissionState.DENY -> result.complete(deny)
                PermissionState.ASK -> dialog().show(
                    title = "Vị trí của bạn",
                    message = "${displayHost(uri)} muốn truy cập vị trí của bạn.",
                    positive = "Cho phép", negative = "Từ chối",
                    neutral = "Chỉ lần này",
                    onNeutral = { result.complete(allow) }
                ) { allowed, _ ->
                    if (!privateRequest) {
                        store.setGeoPermission(origin, if (allowed) PermissionState.ALLOW else PermissionState.DENY)
                    }
                    result.complete(if (allowed) allow else deny)
                }
            }
        }
        return result
    }

    override fun onMediaPermissionRequest(
        session: GeckoSession,
        uri: String,
        video: Array<out PermissionDelegate.MediaSource>?,
        audio: Array<out PermissionDelegate.MediaSource>?,
        callback: PermissionDelegate.MediaCallback
    ) {
        activity.runOnUiThread {
            val videoSource = video?.firstOrNull()
            val audioSource = audio?.firstOrNull()
            if (isDestroyed() || !isActiveSession(session) || !isSecureOrigin(uri) ||
                (videoSource == null && audioSource == null)
            ) {
                callback.reject()
                return@runOnUiThread
            }
            val origin = originKey(uri)
            fun grant() {
                val needed = buildList {
                    if (videoSource != null) add(Manifest.permission.CAMERA)
                    if (audioSource != null) add(Manifest.permission.RECORD_AUDIO)
                }
                requestAndroid(needed) { ok ->
                    if (ok) callback.grant(videoSource, audioSource) else callback.reject()
                }
            }
            // Capture this before the async prompt so closing the tab cannot turn a private request regular.
            val privateRequest = isPrivateSession(session)
            // Private tabs always ask again and never alter the regular profile's saved choice.
            val savedPermission = if (privateRequest) PermissionState.ASK
                else store.getMediaPermission(origin)
            when (savedPermission) {
                PermissionState.ALLOW -> grant()
                PermissionState.DENY -> callback.reject()
                PermissionState.ASK -> {
                    val labels = buildList {
                        if (videoSource != null) add("camera")
                        if (audioSource != null) add("micro")
                    }.joinToString(" và ")
                    dialog().show(
                        title = "Quyền trang web",
                        message = "${displayHost(uri)} muốn sử dụng $labels. Chỉ cho phép nếu bạn tin tưởng trang này.",
                        positive = "Cho phép", negative = "Từ chối",
                        neutral = "Chỉ lần này",
                        onNeutral = { grant() }
                    ) { allowed, _ ->
                        if (!privateRequest) {
                            store.setMediaPermission(origin, if (allowed) PermissionState.ALLOW else PermissionState.DENY)
                        }
                        if (allowed) grant() else callback.reject()
                    }
                }
            }
        }
    }
}
