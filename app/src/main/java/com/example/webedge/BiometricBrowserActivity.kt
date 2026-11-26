package com.example.webedge

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
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
import android.view.View
import android.view.WindowManager
import android.view.WindowInsets
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat

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
        (activity as? BiometricBrowserActivity)?.browserLocked != true
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
    internal fun deferBrowserAction(): Boolean = browserLocked || revealPending ||
        (BrowserLockPolicy.enabled(this) && (!resumed || !lastPolicyEnabled))
    private var authSignal: CancellationSignal? = null
    private var pendingSuccess: (() -> Unit)? = null
    // A successful prompt is not yet permission to reveal the browser: keep the
    // secure surface visible until the foreground-only transition finishes.
    private val unlockHandler = Handler(Looper.getMainLooper())
    private var unlockPending = false
    private val finishUnlock = Runnable {
        if (unlockPending && resumed && browserLocked && !isFinishing && !isDestroyed) {
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
    private var lockDialog: Dialog? = null
    private var lockMessage: TextView? = null
    private var unlockButton: TextView? = null
    private var automaticPrompt = false
    private var concealed = false
    private var originalBackground: Drawable? = null
    private var originalVisibility = View.VISIBLE
    private var originalAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
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
        if (BrowserLockPolicy.enabled(this)) dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    internal fun unregisterBrowserWindow(dialog: Dialog) { browserWindows.remove(dialog) }

    private fun refreshWindowPolicy() {
        val secure = BrowserLockPolicy.enabled(this)
        val wasSecure = lastPolicyEnabled
        lastPolicyEnabled = secure
        val windows = listOf(window) + browserWindows.keys.mapNotNull { it.window }
        for (w in windows) {
            if (secure) w.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            // Do not clear FLAG_SECURE on child windows: other policies may have set it.
            else if (w === window && wasSecure) w.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        setRecentsScreenshotEnabled(!secure)
        onBrowserLockPolicyChanged(secure)
    }

    protected open fun onBrowserLockPolicyChanged(enabled: Boolean) {}
    protected open fun onBrowserUnlocked() {}

    private companion object {
        const val UNLOCK_HOLD_MS = 1500L
        const val REVEAL_FADE_MS = 220L
        const val FRAME_FALLBACK_MS = 300L
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

    private fun lockBrowser() {
        cancelReveal()
        if (!browserLocked) automaticPrompt = true
        browserLocked = true
        if (!concealed) {
            val content = window.decorView.findViewById<View>(android.R.id.content)
            originalBackground = window.decorView.background
            originalVisibility = content?.visibility ?: View.VISIBLE
            originalAccessibility = content?.importantForAccessibility ?: View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            content?.visibility = View.INVISIBLE
            content?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            window.setBackgroundDrawable(lockBackground())
            concealed = true
        }
        // Close website popups and independent sheet windows before displaying the gate.
        browserWindows.values.toList().forEach { it() }
    }

    private fun dismissLockSurface() {
        lockGlyph?.stop(); lockGlyph = null
        lockDialog?.dismiss(); lockDialog = null; lockSurface = null
        lockMessage = null; unlockButton = null
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
        lockSurface?.animate()?.cancel()
        lockSurface?.alpha = 1f
    }

    private fun unlockBrowser() {
        if (!resumed || isFinishing || isDestroyed) return
        cancelUnlockTransition()
        cancelReveal()
        val surface = lockSurface
        val dialog = lockDialog
        revealPending = BrowserLockPolicy.enabled(this) && surface != null && dialog?.isShowing == true
        browserLocked = false
        automaticPrompt = false
        if (concealed) {
            window.setBackgroundDrawable(originalBackground)
            window.decorView.findViewById<View>(android.R.id.content)?.let {
                it.visibility = originalVisibility
                it.importantForAccessibility = originalAccessibility
            }
            originalBackground = null
            concealed = false
        }
        // Keep the opaque lock surface on top while cold-start initialization or
        // warm restore makes the browser visible. No default Dialog exit animation.
        startOnce()
        if (!revealPending || surface == null || dialog == null) {
            dismissLockSurface()
            onBrowserUnlocked()
            return
        }
        val generation = revealGeneration
        fun valid() = generation == revealGeneration && revealPending && resumed &&
            !browserLocked && !isFinishing && !isDestroyed && lockDialog === dialog
        fun finishReveal() {
            if (!valid()) return
            revealPending = false
            dismissLockSurface()
            onBrowserUnlocked()
        }
        var fadeStarted = false
        fun fade() {
            if (!valid() || fadeStarted) return
            fadeStarted = true
            clearFrameCommitCallback()
            revealFallback?.let { unlockHandler.removeCallbacks(it) }
            revealFallback = null
            if (!android.animation.ValueAnimator.areAnimatorsEnabled()) { finishReveal(); return }
            surface.animate().alpha(0f).setDuration(REVEAL_FADE_MS)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .withEndAction { finishReveal() }.start()
        }
        // Prefer a committed frame, with a bounded fallback for occluded/OEM windows.
        val decor = window.decorView
        val fallback = Runnable { fade() }
        revealFallback = fallback
        unlockHandler.postDelayed(fallback, FRAME_FALLBACK_MS)
        if (decor.isHardwareAccelerated && decor.viewTreeObserver.isAlive) {
            val observer = decor.viewTreeObserver
            val callback = Runnable { unlockHandler.post { fade() } }
            frameCommitObserver = observer
            frameCommitCallback = callback
            observer.registerFrameCommitCallback(callback)
            decor.invalidate()
        } else {
            decor.postOnAnimation { decor.postOnAnimation { fade() } }
        }
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
            startLockGlyph()
            if (automaticPrompt && authSignal == null && !unlockPending) {
                automaticPrompt = false
                unlockButton?.post { if (browserLocked && resumed) requestUnlock() }
            }
        }
    }

    override fun onPause() {
        resumed = false
        lockGlyph?.stop()
        if (unlockPending || revealPending) {
            cancelReveal()
            cancelUnlockTransition()
            automaticPrompt = true
            lockMessage?.text = LOCK_INSTRUCTION
            unlockButton?.isEnabled = true
        }
        if (BrowserLockPolicy.enabled(this)) lockBrowser()
        super.onPause()
    }

    override fun onUserLeaveHint() {
        // Home/Recents is a real user departure, unlike a system credential dialog.
        if (browserLocked || revealPending) {
            if (revealPending && BrowserLockPolicy.enabled(this)) lockBrowser()
            cancelReveal()
            cancelUnlockTransition()
            pendingSuccess = null
            val signal = authSignal
            authSignal = null
            signal?.cancel()
            automaticPrompt = true
            lockMessage?.text = LOCK_INSTRUCTION
            unlockButton?.isEnabled = true
        }
        super.onUserLeaveHint()
    }

    override fun onDestroy() {
        // Ignore callbacks from a cancelled prompt or a previous Activity instance.
        val signal = authSignal
        authSignal = null; pendingSuccess = null; initialization = null; resumed = false
        signal?.cancel()
        cancelUnlockTransition()
        cancelReveal()
        dismissLockSurface()
        browserWindows.clear()
        mediaPermissionResult = null; geoPermissionResult = null; syncConsentResult = null
        super.onDestroy()
    }

    private fun startLockGlyph() {
        if (resumed && browserLocked && lockDialog?.isShowing == true &&
            android.animation.ValueAnimator.areAnimatorsEnabled()) lockGlyph?.start()
    }

    private fun cancelUnlockTransition() {
        unlockHandler.removeCallbacks(finishUnlock)
        unlockPending = false
    }

    private fun beginUnlockTransition() {
        if (!resumed || !browserLocked || isFinishing || isDestroyed || unlockPending) return
        showLockScreen()
        automaticPrompt = false
        unlockPending = true
        unlockButton?.isEnabled = false
        lockMessage?.text = "Đã xác thực · Đang mở khóa…"
        // Keep browserLocked, invisible content and FLAG_SECURE intact for 1.5s.
        // The callback is cancelled on pause, user departure, or destruction.
        unlockHandler.postDelayed(finishUnlock, UNLOCK_HOLD_MS)
    }

    private fun showLockScreen() {
        if (lockDialog?.isShowing == true || isFinishing || isDestroyed) return
        val fg = Color.WHITE
        val muted = 0xF2FFFFFF.toInt()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(36), dp(32), dp(36))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            background = lockBackground()
            addView(panel, android.widget.FrameLayout.LayoutParams(-1, -2))
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
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
            isFocusable = true; setOnClickListener { requestUnlock() }
        }
        panel.addView(button, LinearLayout.LayoutParams(-1, dp(52)))
        unlockButton = button
        panel.addView(label("Thoát", 15f, fg, true).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(26).toFloat(); setColor(0x24000000); setStroke(dp(1), 0x80FFFFFF.toInt())
            }
            isFocusable = true; setOnClickListener { cancelUnlockTransition(); finish() }
        }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(10) })
        val dialog = Dialog(this, android.R.style.Theme_Material_NoActionBar)
        dialog.setContentView(scroll)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnDismissListener { glyph.stop() }
        dialog.window?.apply {
            addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            // The scroll surface is opaque until authenticated; a transparent
            // window background permits its controlled exit fade.
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setFormat(PixelFormat.TRANSLUCENT)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setWindowAnimations(0)
            WindowCompat.setDecorFitsSystemWindows(this, false)
            statusBarColor = Color.TRANSPARENT; navigationBarColor = Color.TRANSPARENT
            isStatusBarContrastEnforced = false; isNavigationBarContrastEnforced = false
            WindowCompat.getInsetsController(this, decorView).apply {
                isAppearanceLightStatusBars = false; isAppearanceLightNavigationBars = false
            }
        }
        lockSurface = scroll
        lockDialog = dialog
        dialog.show()
        dialog.window?.setLayout(-1, -1)
        scroll.requestApplyInsets()
        button.isEnabled = authSignal == null && !unlockPending
        startLockGlyph()
    }

    private fun requestUnlock() {
        if (!browserLocked || !resumed || unlockPending) return
        authenticate("Mở khóa Summit", { beginUnlockTransition() }) { message ->
            lockMessage?.text = message
        }
    }

    /** Require authentication to change either direction of the persistent policy. */
    internal fun configureBrowserLock(enable: Boolean, result: (Boolean, String) -> Unit) {
        if (!resumed || browserLocked || authSignal != null) {
            result(false, "Chưa thể thay đổi khóa Summit."); return
        }
        if (enable == BrowserLockPolicy.enabled(this)) { result(true, "Không thay đổi"); return }
        authenticate(if (enable) "Bật khóa Summit" else "Tắt khóa Summit", {
            val persisted = BrowserLockPolicy.persist(this, enable)
            refreshWindowPolicy()
            if (persisted && browserLocked) unlockBrowser()
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
