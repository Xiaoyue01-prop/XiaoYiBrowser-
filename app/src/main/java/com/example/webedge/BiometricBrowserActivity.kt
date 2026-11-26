package com.example.webedge

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.view.WindowManager
import android.view.WindowInsets
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** V3a: local UI access policy. NOT an authentication-bound encryption key. */
internal object BrowserLockPolicy {
    private const val PREFS = "summit_biometric"
    private const val ENABLED = "app_lock"
    const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_STRONG or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL

    fun enabled(context: Context): Boolean = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)

    fun availability(context: Context): Int = context.getSystemService(BiometricManager::class.java)
        ?.canAuthenticate(AUTHENTICATORS) ?: BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE

    fun persist(context: Context, enabled: Boolean): Boolean = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, enabled).commit()

    fun allowBrowserWindow(activity: Activity): Boolean =
        (activity as? BiometricBrowserActivity)?.isLockUiActive() != true
}

/** Both exported browser activities use this gate; no auth result is taken from Intent/Bundle.
 *  Immediate relock on pause. The underlying browser remains alive to preserve tab state.
 *  OS-managed authentication permits Class 3 biometrics or the device credential.
 */
abstract class BiometricBrowserActivity : ComponentActivity() {
    // Register launchers before STARTED, even when cold start waits for biometric auth.
    internal var mediaPermissionResult: (() -> Unit)? = null
    internal var geoPermissionResult: (() -> Unit)? = null
    internal var syncConsentResult: ((ActivityResult) -> Unit)? = null
    internal val firstRunLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    internal val mediaPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { mediaPermissionResult?.invoke() }
    internal val geoPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { geoPermissionResult?.invoke() }
    internal val syncConsentLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { syncConsentResult?.invoke(it) }
    private var lastPolicyEnabled = false
    private var initialization: (() -> Unit)? = null
    protected var browserStarted = false
        private set
    internal var browserLocked = false
        private set
    private var resumed = false
    internal fun isLockUiActive(): Boolean = browserLocked || revealPending
    internal fun deferBrowserAction(): Boolean = isLockUiActive() ||
        (BrowserLockPolicy.enabled(this) && (!resumed || !lastPolicyEnabled))
    private var authSignal: CancellationSignal? = null
    private var pendingSuccess: (() -> Unit)? = null
    // A successful prompt is not yet permission to reveal the browser: keep the
    // secure surface visible until the foreground-only transition finishes.
    private val unlockHandler = Handler(Looper.getMainLooper())
    private var unlockPending = false
    // An in-memory, foreground-only grant. Authentication alone must not reveal the browser.
    private var awaitingUnlockConfirmation = false
    private var captureBlockedByGate = false
    private val finishUnlock = Runnable {
        if (unlockPending && resumed && browserLocked && hasWindowFocus() && !isFinishing && !isDestroyed) {
            unlockPending = false
            unlockBrowser()
        }
    }
    private var revealGeneration = 0L
    private var revealPending = false
    private var revealFallback: Runnable? = null
    private var frameCommitObserver: android.view.ViewTreeObserver? = null
    private var frameCommitCallback: Runnable? = null
    private var lockSurface: View? = null
    private var lockGlyph: BiometricGlyphDrawable? = null
    private var lockMessage: TextView? = null
    private var unlockButton: TextView? = null
    private var automaticPrompt = false
    private var concealed = false
    private var originalBackground: Drawable? = null
    private var originalVisibility = View.VISIBLE
    private var originalAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    private var originalDescendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
    private var originalLightStatusBars = false
    private var originalLightNavigationBars = false
    private var originalStatusBarsVisible = true
    private var originalNavigationBarsVisible = true
    private var originalNavContrast = false
    private val lockBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { /* Use the explicit Exit button while locked. */ }
    }
    private val browserWindows = LinkedHashMap<Dialog, () -> Unit>()

    /** Called from the subclass onCreate, before any Store/WebView/tab restore. */
    protected fun startProtectedBrowser(start: () -> Unit) {
        initialization = start
        refreshWindowPolicy()
        if (BrowserLockPolicy.enabled(this)) lockBrowser() else startOnce()
    }

    private fun startOnce() {
        val start = initialization ?: return
        initialization = null
        browserStarted = true
        start()
    }

    internal fun registerBrowserWindow(dialog: Dialog, close: () -> Unit) {
        browserWindows[dialog] = close
        // Browser windows are normally only created while unlocked. Fail closed if
        // a late registration races the lock; these windows are closed on locking.
        if (isLockUiActive() || (BrowserLockPolicy.enabled(this) && !resumed)) {
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    internal fun unregisterBrowserWindow(dialog: Dialog) { browserWindows.remove(dialog) }

    private fun updateCapturePolicy() {
        val blocked = isLockUiActive() || (BrowserLockPolicy.enabled(this) && !resumed)
        val flag = WindowManager.LayoutParams.FLAG_SECURE
        if (blocked) {
            if ((window.attributes.flags and flag) == 0) {
                window.addFlags(flag)
                captureBlockedByGate = true
            }
        } else if (captureBlockedByGate) {
            // Only release the flag owned by this gate, not an independent policy.
            window.clearFlags(flag)
            captureBlockedByGate = false
        }
    }

    private fun refreshWindowPolicy() {
        val enabled = BrowserLockPolicy.enabled(this)
        lastPolicyEnabled = enabled
        // Recents privacy is independent of foreground screenshots and recording.
        setRecentsScreenshotEnabled(!enabled)
        updateCapturePolicy()
        onBrowserLockPolicyChanged(enabled)
    }

    protected open fun onBrowserLockPolicyChanged(enabled: Boolean) {}
    protected open fun onBrowserUnlocked() {}
    protected open fun unlockVisualWebView(): WebView? = null

    private companion object {
        const val UNLOCK_SETTLE_MS = 120L
        const val REVEAL_SLIDE_MS = 680L
        const val FRAME_FALLBACK_MS = 650L
        const val LOCK_INSTRUCTION = "Xác thực bằng sinh trắc học hoặc mã khóa thiết bị."
        // Exact four digit colors from assets/home/home.js (COLORS), in order.
        val CLOCK_PALETTE = intArrayOf(0xFFD274B5.toInt(), 0xFFA99BFF.toInt(),
            0xFF9B3BA8.toInt(), 0xFF4DA3FF.toInt())
        const val LOCK_SCRIM = 0x73000000 // 45% black, stable contrast on every stop
    }

    private fun lockBackground(): Drawable = LayerDrawable(arrayOf<Drawable>(
        GradientDrawable(GradientDrawable.Orientation.TL_BR, CLOCK_PALETTE.copyOf()),
        ColorDrawable(LOCK_SCRIM)
    ))
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun browserContent(): ViewGroup? = window.decorView.findViewById(android.R.id.content)

    private fun prioritizeLockBack() {
        // A cold-start subclass may install its own back callback during startOnce().
        lockBackCallback.remove()
        lockBackCallback.isEnabled = isLockUiActive()
        onBackPressedDispatcher.addCallback(lockBackCallback)
    }

    private fun lockBrowser() {
        val wasRevealing = revealPending
        cancelReveal()
        if (wasRevealing) restoreBrowserInteraction()
        if (!browserLocked) automaticPrompt = true
        browserLocked = true
        updateCapturePolicy()
        // Secure independent windows before dismissing them on the lock path.
        browserWindows.keys.forEach { it.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        if (!concealed) {
            val content = browserContent()
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            val insets = window.decorView.rootWindowInsets
            originalBackground = window.decorView.background
            originalVisibility = content?.visibility ?: View.VISIBLE
            originalAccessibility = content?.importantForAccessibility ?: View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            originalDescendantFocusability = content?.descendantFocusability ?: ViewGroup.FOCUS_BEFORE_DESCENDANTS
            originalLightStatusBars = controller.isAppearanceLightStatusBars
            originalLightNavigationBars = controller.isAppearanceLightNavigationBars
            originalStatusBarsVisible = insets?.isVisible(WindowInsets.Type.statusBars()) ?: true
            originalNavigationBarsVisible = insets?.isVisible(WindowInsets.Type.navigationBars()) ?: true
            originalNavContrast = window.isNavigationBarContrastEnforced
            content?.visibility = View.INVISIBLE
            content?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            content?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            window.setBackgroundDrawable(lockBackground())
            concealed = true
        }
        // Close website popups and independent sheet windows before displaying the gate.
        browserWindows.values.toList().forEach { it() }
        prioritizeLockBack()
        showLockScreen()
    }

    private fun applyLockBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.ime())
        controller.show(WindowInsetsCompat.Type.systemBars())
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        window.isNavigationBarContrastEnforced = false
    }

    private fun restoreBrowserBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = originalLightStatusBars
        controller.isAppearanceLightNavigationBars = originalLightNavigationBars
        window.isNavigationBarContrastEnforced = originalNavContrast
        if (!originalStatusBarsVisible) controller.hide(WindowInsetsCompat.Type.statusBars())
        if (!originalNavigationBarsVisible) controller.hide(WindowInsetsCompat.Type.navigationBars())
    }

    private fun restoreBrowserInteraction() {
        browserContent()?.let {
            it.importantForAccessibility = originalAccessibility
            it.descendantFocusability = originalDescendantFocusability
        }
    }

    private fun dismissLockSurface() {
        lockGlyph?.stop(); lockGlyph = null
        lockSurface?.let { surface -> (surface.parent as? ViewGroup)?.removeView(surface) }
        lockSurface = null
        lockMessage = null; unlockButton = null
        lockBackCallback.isEnabled = false
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (revealPending || (browserLocked && (unlockPending || lockSurface == null))) return true
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Never let keyboard shortcuts reach the browser behind the moving surface.
        if (revealPending || (browserLocked && (unlockPending || lockSurface == null))) return true
        return super.dispatchKeyEvent(event)
    }

    private fun clearFrameCommitCallback() {
        val observer = frameCommitObserver
        val callback = frameCommitCallback
        if (observer?.isAlive == true && callback != null) observer.unregisterFrameCommitCallback(callback)
        frameCommitObserver = null
        frameCommitCallback = null
    }

    private fun cancelReveal() {
        ++revealGeneration
        revealPending = false
        clearFrameCommitCallback()
        revealFallback?.let { unlockHandler.removeCallbacks(it) }
        revealFallback = null
        lockSurface?.animate()?.withEndAction(null)?.cancel()
        lockSurface?.translationY = 0f
        lockSurface?.alpha = 1f
    }

    private fun unlockBrowser() {
        if (!resumed || isFinishing || isDestroyed) return
        cancelUnlockTransition()
        awaitingUnlockConfirmation = false
        cancelReveal()
        val surface = lockSurface
        revealPending = BrowserLockPolicy.enabled(this) && surface?.isAttachedToWindow == true
        browserLocked = false
        automaticPrompt = false
        val wasConcealed = concealed
        if (wasConcealed) {
            // Restore the browser while it is still covered by the opaque decor overlay.
            restoreBrowserBars()
            window.setBackgroundDrawable(originalBackground)
            browserContent()?.visibility = originalVisibility
            originalBackground = null
            concealed = false
        }
        startOnce()
        // setContentView() only replaces the content container, not its decor sibling.
        if (wasConcealed && !revealPending) restoreBrowserInteraction()
        if (!revealPending || surface == null) {
            dismissLockSurface()
            updateCapturePolicy()
            onBrowserUnlocked()
            return
        }
        prioritizeLockBack()
        surface.bringToFront()
        val generation = revealGeneration
        fun valid() = generation == revealGeneration && revealPending && resumed &&
            !browserLocked && !isFinishing && !isDestroyed && lockSurface === surface && surface.isAttachedToWindow
        fun finishReveal() {
            if (!valid()) return
            revealPending = false
            restoreBrowserInteraction()
            dismissLockSurface()
            updateCapturePolicy()
            onBrowserUnlocked()
        }
        var slideStarted = false
        fun slide() {
            if (!valid() || slideStarted) return
            slideStarted = true
            clearFrameCommitCallback()
            revealFallback?.let { unlockHandler.removeCallbacks(it) }
            revealFallback = null
            if (!android.animation.ValueAnimator.areAnimatorsEnabled() || surface.height <= 0) {
                finishReveal(); return
            }
            // A single compositor transform: the opaque background, glyph and buttons
            // move as one surface. No crossfade, window dismissal, or per-frame relayout.
            surface.animate().translationY(-surface.height.toFloat()).setDuration(REVEAL_SLIDE_MS)
                // Softer launch and a longer deceleration tail; no spring overshoot.
                .setInterpolator(PathInterpolator(0.30f, 0.35f, 0.32f, 1f))
                .withEndAction { finishReveal() }.start()
        }
        val decor = window.decorView
        var frameRequested = false
        fun awaitCommittedFrame() {
            if (!valid() || slideStarted || frameRequested) return
            frameRequested = true
            if (decor.isHardwareAccelerated && decor.viewTreeObserver.isAlive) {
                val observer = decor.viewTreeObserver
                val callback = Runnable { surface.postOnAnimation { slide() } }
                frameCommitObserver = observer
                frameCommitCallback = callback
                observer.registerFrameCommitCallback(callback)
                decor.invalidate()
            } else {
                decor.postOnAnimation { decor.postOnAnimation { slide() } }
            }
        }
        // WebView has an independent renderer; wait for its visual state when possible,
        // then for the Activity frame. Never deadlock on a slow/hidden renderer.
        val fallback = Runnable { slide() }
        revealFallback = fallback
        unlockHandler.postDelayed(fallback, FRAME_FALLBACK_MS)
        val web = unlockVisualWebView()
        if (web != null && web.isAttachedToWindow && web.isShown) {
            try {
                web.postVisualStateCallback(generation, object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) { awaitCommittedFrame() }
                })
            } catch (_: RuntimeException) { awaitCommittedFrame() }
        } else {
            awaitCommittedFrame()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && resumed && browserLocked) {
            applyLockBars()
            if (unlockPending) scheduleUnlockWhenFocused()
            else {
                startLockGlyph()
                maybeRequestAutomaticUnlock()
            }
        }
    }

    private fun maybeRequestAutomaticUnlock() {
        if (!resumed || !hasWindowFocus() || !browserLocked || !automaticPrompt ||
            authSignal != null || unlockPending || awaitingUnlockConfirmation) return
        automaticPrompt = false
        requestUnlock()
    }

    override fun onPostResume() {
        super.onPostResume()
        resumed = true
        if (BrowserLockPolicy.enabled(this)) {
            // Another Summit activity may have enabled the setting while this one was paused.
            val newlyEnabled = !lastPolicyEnabled
            refreshWindowPolicy()
            if (!browserLocked && (newlyEnabled || !browserStarted)) lockBrowser()
        } else {
            if (lastPolicyEnabled) refreshWindowPolicy()
            if (browserLocked && authSignal == null && pendingSuccess == null) unlockBrowser()
        }
        val success = pendingSuccess
        pendingSuccess = null
        success?.invoke()
        if (browserLocked) {
            showLockScreen()
            applyLockBars()
            startLockGlyph()
            unlockButton?.post { maybeRequestAutomaticUnlock() }
        }
    }

    override fun onPause() {
        resumed = false
        lockGlyph?.stop()
        if (awaitingUnlockConfirmation || unlockPending || revealPending) {
            resetUnlockConfirmation()
        }
        if (BrowserLockPolicy.enabled(this)) lockBrowser()
        else if (revealPending) {
            cancelReveal()
            restoreBrowserInteraction()
            dismissLockSurface()
            updateCapturePolicy()
        }
        super.onPause()
    }

    override fun onUserLeaveHint() {
        // Home/Recents is a real user departure, unlike a system credential dialog.
        if (browserLocked || revealPending) {
            if (revealPending && BrowserLockPolicy.enabled(this)) lockBrowser()
            cancelReveal()
            pendingSuccess = null
            val signal = authSignal
            authSignal = null
            signal?.cancel()
            resetUnlockConfirmation()
        }
        super.onUserLeaveHint()
    }

    override fun onDestroy() {
        // Ignore callbacks from a cancelled prompt or a previous Activity instance.
        val signal = authSignal
        authSignal = null; pendingSuccess = null; initialization = null; resumed = false
        signal?.cancel()
        awaitingUnlockConfirmation = false
        cancelUnlockTransition()
        cancelReveal()
        dismissLockSurface()
        lockBackCallback.remove()
        browserWindows.clear()
        mediaPermissionResult = null; geoPermissionResult = null; syncConsentResult = null
        super.onDestroy()
    }

    private fun startLockGlyph() {
        if (resumed && browserLocked && !unlockPending &&
            lockSurface?.isAttachedToWindow == true &&
            android.animation.ValueAnimator.areAnimatorsEnabled()) {
            if (awaitingUnlockConfirmation) lockGlyph?.startConfirmationSpin() else lockGlyph?.start()
        }
    }

    private fun cancelUnlockTransition() {
        unlockHandler.removeCallbacks(finishUnlock)
        unlockPending = false
    }

    private fun resetUnlockConfirmation() {
        lockGlyph?.stop()
        awaitingUnlockConfirmation = false
        cancelUnlockTransition()
        automaticPrompt = true
        lockMessage?.text = LOCK_INSTRUCTION
        unlockButton?.isEnabled = authSignal == null
    }

    private fun markUnlockAuthenticated() {
        if (!resumed || !browserLocked || isFinishing || isDestroyed || unlockPending) return
        awaitingUnlockConfirmation = true
        automaticPrompt = false
        showLockScreen()
        lockMessage?.text = "Đã xác thực · Nhấn Mở khóa Summit để tiếp tục."
        unlockButton?.isEnabled = true
        startLockGlyph()
    }

    private fun beginUnlockTransition() {
        if (!awaitingUnlockConfirmation || !resumed || !hasWindowFocus() || !browserLocked ||
            isFinishing || isDestroyed || unlockPending) return
        awaitingUnlockConfirmation = false
        showLockScreen()
        automaticPrompt = false
        unlockPending = true
        unlockButton?.isEnabled = false
        lockMessage?.text = "Đã xác thực · Đang mở khóa…"
        lockGlyph?.freeze()
        // Let the system authentication prompt give focus back before preparing the reveal.
        scheduleUnlockWhenFocused()
    }

    private fun scheduleUnlockWhenFocused() {
        unlockHandler.removeCallbacks(finishUnlock)
        if (hasWindowFocus()) unlockHandler.postDelayed(finishUnlock, UNLOCK_SETTLE_MS)
    }

    private fun showLockScreen() {
        if (lockSurface != null || isFinishing || isDestroyed) return
        val fg = Color.WHITE
        val muted = 0xF2FFFFFF.toInt()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(36), dp(32), dp(36))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isClickable = true
            isFocusableInTouchMode = true
            elevation = dp(64).toFloat()
            outlineProvider = null // Z-order only; no shadow along the moving edge.
            overScrollMode = View.OVER_SCROLL_NEVER
            background = lockBackground()
            addView(panel, android.widget.FrameLayout.LayoutParams(-1, -2))
            setOnApplyWindowInsetsListener { view, insets ->
                if (browserLocked && !revealPending) {
                    val bars = (view.rootWindowInsets ?: insets).getInsets(
                        WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                }
                insets
            }
        }
        fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
            text = value; textSize = size; setTextColor(color); gravity = Gravity.CENTER
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
        val glyph = BiometricGlyphDrawable(fg)
        lockGlyph = glyph
        panel.addView(ImageView(this).apply {
            setImageDrawable(glyph); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(88), dp(88)).apply { bottomMargin = dp(26) })
        panel.addView(label("Summit đã khóa", 26f, fg, true))
        val message = label(LOCK_INSTRUCTION, 14f, muted).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        panel.addView(message, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12); bottomMargin = dp(28) })
        lockMessage = message
        val button = label("Mở khóa Summit", 16f, Color.rgb(34, 24, 58), true).apply {
            background = GradientDrawable().apply { cornerRadius = dp(26).toFloat(); setColor(Color.WHITE) }
            isFocusable = true
            setOnClickListener {
                if (awaitingUnlockConfirmation) beginUnlockTransition() else requestUnlock()
            }
        }
        panel.addView(button, LinearLayout.LayoutParams(-1, dp(52)))
        unlockButton = button
        panel.addView(label("Thoát", 15f, fg, true).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(26).toFloat(); setColor(0x24000000); setStroke(dp(1), 0x80FFFFFF.toInt())
            }
            isFocusable = true; setOnClickListener { cancelUnlockTransition(); finish() }
        }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(10) })
        // Attach outside android.R.id.content so cold-start setContentView() cannot
        // remove the lock surface. Both layers now share the same Activity window.
        val decor = window.decorView as FrameLayout
        // The lock screen is opaque and has its own palette. In particular, its
        // white unlock button must never receive the dark-page white foreground.
        GlassSystem.preserveForegroundPalette(scroll)
        lockSurface = scroll
        decor.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        scroll.requestedFrameRate = View.REQUESTED_FRAME_RATE_CATEGORY_HIGH
        scroll.bringToFront()
        scroll.requestFocus()
        applyLockBars()
        scroll.requestApplyInsets()
        button.isEnabled = authSignal == null && !unlockPending
        startLockGlyph()
    }

    private fun requestUnlock() {
        if (!browserLocked || !resumed || unlockPending || awaitingUnlockConfirmation) return
        authenticate("Mở khóa Summit", { markUnlockAuthenticated() }) { message ->
            lockMessage?.text = message
        }
    }

    /** Require authentication to change either direction of the persistent policy. */
    internal fun configureBrowserLock(enable: Boolean, result: (Boolean, String) -> Unit) {
        if (!resumed || isLockUiActive() || authSignal != null) {
            result(false, "Chưa thể thay đổi khóa Summit."); return
        }
        if (enable == BrowserLockPolicy.enabled(this)) { result(true, "Không thay đổi"); return }
        authenticate(if (enable) "Bật khóa Summit" else "Tắt khóa Summit", {
            val persisted = BrowserLockPolicy.persist(this, enable)
            refreshWindowPolicy()
            if (persisted && !enable && browserLocked) unlockBrowser()
            result(persisted, if (persisted) {
                if (enable) "Đã bật khóa Summit" else "Đã tắt khóa Summit"
            } else "Không lưu được cài đặt khóa")
        }) { message -> result(false, message) }
    }

    private fun authenticate(title: String, success: () -> Unit, failure: (String) -> Unit) {
        if (authSignal != null || !resumed || isFinishing || isDestroyed) return
        if (BrowserLockPolicy.availability(this) != BiometricManager.BIOMETRIC_SUCCESS) {
            failure("Không có phương thức xác thực phù hợp. Hãy kiểm tra mã khóa hoặc sinh trắc học trong cài đặt Android.")
            return
        }
        val signal = CancellationSignal()
        authSignal = signal
        unlockButton?.isEnabled = false
        try {
            BiometricPrompt.Builder(this)
                .setTitle(title)
                .setSubtitle("Xác thực qua hệ thống Android")
                .setAllowedAuthenticators(BrowserLockPolicy.AUTHENTICATORS)
                .setConfirmationRequired(true)
                .build().authenticate(signal, mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (authSignal !== signal || isDestroyed || isFinishing) return
                        authSignal = null
                        unlockButton?.isEnabled = true
                        // Device credential may return while Activity is paused; do not reveal UI until resumed.
                        if (resumed) success() else pendingSuccess = success
                    }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (authSignal !== signal) return
                        authSignal = null; unlockButton?.isEnabled = true
                        failure(errString.toString())
                    }
                    override fun onAuthenticationFailed() { if (authSignal === signal) lockMessage?.text = "Chưa xác thực được. Hãy thử lại." }
                })
        } catch (_: Exception) {
            authSignal = null; unlockButton?.isEnabled = true
            failure("Không mở được xác thực hệ thống.")
        }
    }
}
