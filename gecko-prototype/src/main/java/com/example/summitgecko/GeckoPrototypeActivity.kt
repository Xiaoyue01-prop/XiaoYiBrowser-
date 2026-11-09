package com.example.summitgecko

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.net.Uri
import android.util.Log
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.PixelCopy
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension

/**
 * Engine spike v4 (public API only).
 *
 * Three modes (button cycles them):
 *  0 Below       Gecko sits under the status bar (like Chrome). Strip colour = sampled page top.
 *  1 Edge JS     Gecko extends under the status bar, safeAreaInsetsChanged is fed, and the
 *                bundled WebExtension ("Summit Probe") moves fixed/sticky headers down by
 *                env(safe-area-inset-top).
 *  2 Edge Native Gecko extends under the status bar, insets are 0 and setDynamicToolbarMaxHeight
 *                is set to the status bar height (does Gecko push fixed headers down itself?).
 *
 * In both Edge modes the strip above the page is the header colour when the extension reports a
 * pinned header, and a blurred copy of the page top otherwise (content scrolls under glass).
 */
class GeckoPrototypeActivity : Activity() {

    companion object {
        @Volatile
        private var sharedRuntime: GeckoRuntime? = null

        private fun runtime(activity: Activity): GeckoRuntime =
            sharedRuntime ?: synchronized(this) {
                sharedRuntime ?: GeckoRuntime.create(activity)
                    .also { sharedRuntime = it }
            }

        private const val TEST_URL = "resource://android/assets/gecko_test/default.html"
        private const val PROBE_URI = "resource://android/assets/summit_probe/"
        private const val PROBE_ID = "summit-probe@example.com"
        private const val PROBE_LOG_TAG = "SummitProbe"
        private const val SAMPLE_INTERVAL_MS = 300L
        private val MODE_NAMES = arrayOf("Below", "Edge JS", "Edge Native")
    }

    private lateinit var root: FrameLayout
    private lateinit var surfaceView: GeckoSurfaceView
    private lateinit var topStrip: ImageView
    private lateinit var controls: LinearLayout
    private lateinit var info: TextView
    private lateinit var urlField: EditText
    private lateinit var modeButton: Button
    private lateinit var session: GeckoSession
    private lateinit var insetsController: WindowInsetsControllerCompat

    private val ui = Handler(Looper.getMainLooper())
    private var statusTop = 0
    private var controlsHeight = 0
    private var mode = 0
    private var fullscreen = false
    private var sampledColor = Color.BLACK
    private var copyState = "waiting"

    // Probe (WebExtension) state
    private var probeState = "starting"
    private var probeMessages = 0
    private var probeHasFixed = false
    private var probeColor: Int? = null
    private var probeShifted = 0
    private var probeInset = 0.0

    private val isEdge: Boolean get() = mode != 0

    private val probeDelegate = object : WebExtension.MessageDelegate {
        override fun onMessage(
            nativeApp: String,
            message: Any,
            sender: WebExtension.MessageSender
        ): GeckoResult<Any>? {
            // Keep this log before parsing/validation: it proves the native callback was reached.
            Log.i(
                PROBE_LOG_TAG,
                "onMessage nativeApp=$nativeApp env=${sender.environmentType} " +
                    "url=${sender.url} message=$message"
            )

            val payload = message as? JSONObject
            if (payload == null) {
                runOnUiThread {
                    probeState = "message received (non-JSON: ${message.javaClass.simpleName})"
                    updateInfo()
                }
                return null
            }

            runOnUiThread {
                when (payload.optString("type")) {
                    "background-start" -> {
                        probeState = "background msg OK · waiting content script"
                        updateInfo()
                    }
                    "probe-start" -> {
                        probeState = "content injected · native msg OK · ${sender.url.substringAfter("//").take(34)}"
                        updateInfo()
                    }
                    "top" -> handleProbe(payload)
                    else -> {
                        probeState = "message received · type=${payload.optString("type", "?")}"
                        updateInfo()
                    }
                }
            }
            return null
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            sampleTop()
            ui.postDelayed(this, SAMPLE_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        surfaceView = GeckoSurfaceView(this)
        root.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        topStrip = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setBackgroundColor(Color.BLACK)
        }
        root.addView(
            topStrip,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                0,
                Gravity.TOP
            )
        )

        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.argb(240, 18, 22, 30))
            elevation = dp(12).toFloat()
        }

        info = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 10.5f
            maxLines = 3
        }
        controls.addView(info)

        urlField = EditText(this).apply {
            hint = "URL hoặc từ khoá"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            textSize = 14f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    go()
                    true
                } else {
                    false
                }
            }
        }
        controls.addView(
            urlField,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        modeButton = makeButton("Mode: Below") {
            mode = (mode + 1) % MODE_NAMES.size
            resetProbeState()
            if (!isEdge) {
                setStripSolid(sampledColor)
            }
            copyState = "waiting"
            updateLayout()
            if (::session.isInitialized) session.reload()
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        buttons.addView(makeButton("Go") { go() }, weighted())
        buttons.addView(makeButton("Test") {
            resetProbeState()
            if (::session.isInitialized) session.loadUri(TEST_URL)
        }, weighted(1.6f))
        buttons.addView(modeButton, weighted(2f))
        buttons.addView(makeButton("Reload") {
            resetProbeState()
            if (::session.isInitialized) session.reload()
        }, weighted())
        controls.addView(
            buttons,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
            )
        )

        root.addView(
            controls,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )

        controls.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val h = bottom - top
            if (h != controlsHeight && right - left > 0) {
                controlsHeight = h
                updateLayout()
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            if (!fullscreen) statusTop = bars.top
            controls.setPadding(dp(12), dp(8), dp(12), maxOf(bars.bottom, ime.bottom) + dp(8))
            updateLayout()
            insets
        }

        setContentView(root)
        ViewCompat.requestApplyInsets(root)

        session = GeckoSession()
        session.setContentDelegate(object : GeckoSession.ContentDelegate {
            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                runOnUiThread { setFullscreen(fullScreen) }
            }
        })
        session.open(runtime(this))
        surfaceView.attach(session)
        installProbe()
        updateLayout()
        session.loadUri(TEST_URL)
    }

    // ---- WebExtension probe ---------------------------------------------------------------

    private fun installProbe() {
        try {
            runtime(this).webExtensionController
                .ensureBuiltIn(PROBE_URI, PROBE_ID)
                .accept({ ext ->
                    runOnUiThread {
                        if (ext == null) {
                            probeState = "ext null"
                        } else {
                            // Register both routes. Content scripts require the session-specific
                            // delegate; the extension-level delegate is for background scripts.
                            session.webExtensionController
                                .setMessageDelegate(ext, probeDelegate, "browser")
                            ext.setMessageDelegate(probeDelegate, "browser")
                            probeState = "ext ok · waiting probe-start"
                            Log.i(PROBE_LOG_TAG, "Built-in extension installed; delegates registered")
                        }
                        updateInfo()
                    }
                }, { err ->
                    runOnUiThread {
                        probeState = "ext failed: ${err?.message}"
                        updateInfo()
                    }
                })
        } catch (e: Exception) {
            probeState = "ext error ${e.javaClass.simpleName}"
            updateInfo()
        }
    }

    private fun handleProbe(m: JSONObject) {
        probeMessages++
        probeHasFixed = m.optBoolean("hasFixed", false)
        probeColor = parseCssColor(m.optString("color", ""))
        probeShifted = m.optInt("shifted", 0)
        probeInset = m.optDouble("inset", 0.0)
        probeState = "msgs=$probeMessages fixed=$probeHasFixed shifted=$probeShifted " +
            "inset=${"%.1f".format(probeInset)}"
        if (isEdge) sampleTop()
        updateInfo()
    }

    private fun resetProbeState() {
        probeHasFixed = false
        probeColor = null
        probeShifted = 0
    }

    private fun parseCssColor(css: String): Int? {
        val m = Regex("""rgba?\(\s*(\d+)[,\s]+(\d+)[,\s]+(\d+)(?:[,\s/]+([\d.]+))?\s*\)""")
            .find(css) ?: return null
        val alpha = m.groupValues[4].toDoubleOrNull() ?: 1.0
        if (alpha < 0.5) return null
        return Color.rgb(
            m.groupValues[1].toInt().coerceIn(0, 255),
            m.groupValues[2].toInt().coerceIn(0, 255),
            m.groupValues[3].toInt().coerceIn(0, 255)
        )
    }

    // ---- layout / modes -------------------------------------------------------------------

    private fun updateLayout() {
        if (!::surfaceView.isInitialized || !::modeButton.isInitialized) return

        val top = if (fullscreen || isEdge) 0 else statusTop
        val bottom = if (fullscreen) 0 else controlsHeight
        val lp = surfaceView.layoutParams as FrameLayout.LayoutParams
        if (lp.topMargin != top || lp.bottomMargin != bottom) {
            lp.topMargin = top
            lp.bottomMargin = bottom
            surfaceView.layoutParams = lp
        }

        val stripHeight = if (fullscreen) 0 else statusTop
        val sp = topStrip.layoutParams
        if (sp.height != stripHeight) {
            sp.height = stripHeight
            topStrip.layoutParams = sp
        }

        controls.visibility = if (fullscreen) View.GONE else View.VISIBLE
        val feedInset = mode == 1 && !fullscreen
        val feedToolbar = mode == 2 && !fullscreen
        surfaceView.setSafeInsets(if (feedInset) statusTop else 0, 0, 0, 0)
        surfaceView.setDynamicToolbarMaxHeight(if (feedToolbar) statusTop else 0)
        modeButton.text = "Mode: ${MODE_NAMES[mode]}"
        updateInfo()
    }

    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        if (on) {
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
        updateLayout()
    }

    // ---- status bar strip -----------------------------------------------------------------

    private fun luminance(c: Int): Double =
        0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)

    private fun setStripSolid(c: Int) {
        sampledColor = c
        topStrip.setImageDrawable(null)
        topStrip.setRenderEffect(null)
        topStrip.colorFilter = null
        topStrip.setBackgroundColor(c)
        insetsController.isAppearanceLightStatusBars = luminance(c) > 150
    }

    private fun sampleTop() {
        if (fullscreen || !::surfaceView.isInitialized) return
        if (!surfaceView.isSurfaceReady || surfaceView.width <= 0 || statusTop <= 0) return
        try {
            if (!isEdge) {
                sampleColor()
            } else {
                val c = probeColor
                if (probeHasFixed && c != null) {
                    setStripSolid(c)
                    copyState = "probe colour"
                    updateInfo()
                } else {
                    sampleGlass()
                }
            }
        } catch (e: Exception) {
            copyState = "error ${e.javaClass.simpleName}"
            updateInfo()
        }
    }

    private fun sampleColor() {
        val w = surfaceView.width
        val h = minOf(6, surfaceView.height)
        if (h <= 0) return
        val bmp = Bitmap.createBitmap(8, 1, Bitmap.Config.ARGB_8888)
        PixelCopy.request(
            surfaceView,
            Rect(0, 0, w, h),
            bmp,
            PixelCopy.OnPixelCopyFinishedListener { code ->
                if (code == PixelCopy.SUCCESS) {
                    copyState = "surface ok"
                    applyColor(bmp)
                } else {
                    sampleColorFromWindow(code)
                }
            },
            ui
        )
    }

    private fun sampleColorFromWindow(surfaceCode: Int) {
        try {
            val loc = IntArray(2)
            surfaceView.getLocationInWindow(loc)
            val rect = Rect(loc[0], loc[1], loc[0] + surfaceView.width, loc[1] + 6)
            val bmp = Bitmap.createBitmap(8, 1, Bitmap.Config.ARGB_8888)
            PixelCopy.request(
                window,
                rect,
                bmp,
                PixelCopy.OnPixelCopyFinishedListener { code ->
                    if (code == PixelCopy.SUCCESS) {
                        copyState = "window ok (surface=$surfaceCode)"
                        applyColor(bmp)
                    } else {
                        copyState = "failed surface=$surfaceCode window=$code"
                        updateInfo()
                    }
                },
                ui
            )
        } catch (e: Exception) {
            copyState = "failed surface=$surfaceCode window=${e.javaClass.simpleName}"
            updateInfo()
        }
    }

    private fun sampleGlass() {
        val w = surfaceView.width
        val h = statusTop
        if (h <= 0 || surfaceView.height < h) return
        val bmp = Bitmap.createBitmap(maxOf(1, w / 8), maxOf(1, h / 8), Bitmap.Config.ARGB_8888)
        PixelCopy.request(
            surfaceView,
            Rect(0, 0, w, h),
            bmp,
            PixelCopy.OnPixelCopyFinishedListener { code ->
                if (code == PixelCopy.SUCCESS) {
                    copyState = "glass ok"
                    topStrip.setImageBitmap(bmp)
                    topStrip.setBackgroundColor(Color.TRANSPARENT)
                    topStrip.setRenderEffect(
                        RenderEffect.createBlurEffect(16f, 16f, Shader.TileMode.CLAMP)
                    )
                    topStrip.colorFilter = PorterDuffColorFilter(
                        Color.argb(60, 18, 22, 30),
                        PorterDuff.Mode.SRC_ATOP
                    )
                } else {
                    copyState = "glass failed surface=$code"
                }
                updateInfo()
            },
            ui
        )
    }

    private fun applyColor(bmp: Bitmap) {
        val px = IntArray(8)
        bmp.getPixels(px, 0, 8, 0, 0, 8, 1)
        // Median by luminance: ignores a logo or button sitting in the sampled row.
        val c = px.sortedBy { luminance(it) }[4] or (0xFF shl 24)
        setStripSolid(c)
        updateInfo()
    }

    private fun updateInfo() {
        if (!::info.isInitialized) return
        info.text = "GeckoView 159 · ${MODE_NAMES[mode]} · bar ${statusTop}px · copy: $copyState\n" +
            "probe: $probeState"
    }

    // ---- navigation -----------------------------------------------------------------------

    private fun go() {
        val t = urlField.text.toString().trim()
        if (t.isEmpty() || !::session.isInitialized) return
        val url = when {
            t.contains("://") -> t
            t.contains(' ') || !t.contains('.') -> "https://duckduckgo.com/?q=" + Uri.encode(t)
            else -> "https://$t"
        }
        resetProbeState()
        session.loadUri(url)
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(urlField.windowToken, 0)
        urlField.clearFocus()
    }

    private fun makeButton(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 12f
            isAllCaps = false
            setOnClickListener { action() }
        }

    private fun weighted(weight: Float = 1f) = LinearLayout.LayoutParams(
        0,
        LinearLayout.LayoutParams.MATCH_PARENT,
        weight
    ).apply {
        marginStart = dp(3)
        marginEnd = dp(3)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    // ---- lifecycle ------------------------------------------------------------------------

    override fun onResume() {
        super.onResume()
        if (::session.isInitialized) {
            session.setActive(true)
            session.setFocused(true)
        }
        ui.removeCallbacks(tick)
        ui.postDelayed(tick, SAMPLE_INTERVAL_MS)
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        if (::session.isInitialized) {
            session.setFocused(false)
            session.setActive(false)
        }
        super.onPause()
    }

    override fun onDestroy() {
        ui.removeCallbacks(tick)
        if (::surfaceView.isInitialized) surfaceView.detach()
        if (::session.isInitialized && session.isOpen) session.close()
        super.onDestroy()
    }
}
