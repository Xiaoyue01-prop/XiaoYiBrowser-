package com.example.webedge

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts

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
    internal fun deferBrowserAction(): Boolean = browserLocked ||
        (BrowserLockPolicy.enabled(this) && (!resumed || !lastPolicyEnabled))
    private var authSignal: CancellationSignal? = null
    private var pendingSuccess: (() -> Unit)? = null
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

    private fun dark() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private fun surface() = if (dark()) Color.rgb(20, 20, 24) else Color.rgb(248, 248, 250)
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun lockBrowser() {
        if (!browserLocked) automaticPrompt = true
        browserLocked = true
        if (!concealed) {
            val content = window.decorView.findViewById<View>(android.R.id.content)
            originalBackground = window.decorView.background
            originalVisibility = content?.visibility ?: View.VISIBLE
            originalAccessibility = content?.importantForAccessibility ?: View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            content?.visibility = View.INVISIBLE
            content?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            window.setBackgroundDrawable(ColorDrawable(surface()))
            concealed = true
        }
        // Close website popups and independent sheet windows before displaying the gate.
        browserWindows.values.toList().forEach { it() }
    }

    private fun unlockBrowser() {
        if (!resumed || isFinishing || isDestroyed) return
        browserLocked = false
        automaticPrompt = false
        lockDialog?.dismiss(); lockDialog = null; lockMessage = null; unlockButton = null
        if (concealed) {
            window.setBackgroundDrawable(originalBackground)
            window.decorView.findViewById<View>(android.R.id.content)?.let {
                it.visibility = originalVisibility
                it.importantForAccessibility = originalAccessibility
            }
            originalBackground = null
            concealed = false
        }
        startOnce()
        onBrowserUnlocked()
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
            if (automaticPrompt && authSignal == null) {
                automaticPrompt = false
                unlockButton?.post { if (browserLocked && resumed) requestUnlock() }
            }
        }
    }

    override fun onPause() {
        resumed = false
        if (BrowserLockPolicy.enabled(this)) lockBrowser()
        super.onPause()
    }

    override fun onDestroy() {
        // Ignore callbacks from a cancelled prompt or a previous Activity instance.
        val signal = authSignal
        authSignal = null; pendingSuccess = null; initialization = null; resumed = false
        signal?.cancel()
        lockDialog?.dismiss(); lockDialog = null
        browserWindows.clear()
        mediaPermissionResult = null; geoPermissionResult = null; syncConsentResult = null
        super.onDestroy()
    }

    private fun showLockScreen() {
        if (lockDialog?.isShowing == true || isFinishing || isDestroyed) return
        val fg = if (dark()) Color.WHITE else Color.rgb(24, 24, 27)
        val muted = if (dark()) Color.rgb(192, 192, 200) else Color.rgb(95, 95, 105)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(36), dp(32), dp(36))
            setBackgroundColor(surface())
        }
        fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
            text = value; textSize = size; setTextColor(color); gravity = Gravity.CENTER
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
        panel.addView(ImageView(this).apply {
            setImageDrawable(PathIcon(Icons.SHIELD, fg)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(56), dp(56)).apply { bottomMargin = dp(20) })
        panel.addView(label("Summit đã khóa", 26f, fg, true))
        val message = label("Xác thực bằng sinh trắc học hoặc mã khóa thiết bị.", 14f, muted)
        panel.addView(message, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12); bottomMargin = dp(24) })
        lockMessage = message
        val button = label("Mở khóa Summit", 16f, Color.WHITE, true).apply {
            background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(0xFF0877D9.toInt()) }
            isFocusable = true; setOnClickListener { requestUnlock() }
        }
        panel.addView(button, LinearLayout.LayoutParams(-1, dp(52)))
        unlockButton = button
        panel.addView(label("Thoát", 15f, fg).apply {
            isFocusable = true; setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(10) })
        val dialog = Dialog(this, android.R.style.Theme_Material_NoActionBar)
        dialog.setContentView(panel)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.apply {
            addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            setBackgroundDrawable(ColorDrawable(surface()))
            statusBarColor = surface(); navigationBarColor = surface()
        }
        lockDialog = dialog
        dialog.show()
        dialog.window?.setLayout(-1, -1)
        button.isEnabled = authSignal == null
    }

    private fun requestUnlock() {
        if (!browserLocked || !resumed) return
        authenticate("Mở khóa Summit", { unlockBrowser() }) { message ->
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
