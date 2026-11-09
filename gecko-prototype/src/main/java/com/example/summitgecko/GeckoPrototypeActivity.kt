package com.example.summitgecko

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession

/**
 * Engine spike v2. Uses a custom GeckoSurfaceView (public GeckoSession.acquireDisplay API)
 * instead of the stock GeckoView widget, so the app can feed GeckoDisplay.safeAreaInsetsChanged.
 *
 * Experiment: load the cover page, press "Inset", and see whether env(safe-area-inset-top)
 * on the page changes from 0 to the status bar height.
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

        private const val COVER_URL = "resource://android/assets/gecko_test/cover.html"
        private const val DEFAULT_URL = "resource://android/assets/gecko_test/default.html"
    }

    private lateinit var surfaceView: GeckoSurfaceView
    private lateinit var session: GeckoSession
    private lateinit var stateLabel: TextView
    private lateinit var insetButton: Button

    private var statusTop = 0
    private var insetOn = false
    private var pageLabel = "viewport-fit=cover"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        surfaceView = GeckoSurfaceView(this)
        root.addView(
            surfaceView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.argb(232, 18, 22, 30))
            elevation = dp(12).toFloat()
        }

        stateLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
        }
        controls.addView(
            stateLabel,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(28)
            )
        )

        insetButton = makeButton("Inset: off") {
            insetOn = !insetOn
            applyInsets()
            // Reload so the page re-evaluates env() from scratch.
            if (::session.isInitialized) session.reload()
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        buttons.addView(makeButton("Cover") { loadTestPage(COVER_URL, "viewport-fit=cover") }, weighted())
        buttons.addView(makeButton("No cover") { loadTestPage(DEFAULT_URL, "default viewport") }, weighted())
        buttons.addView(insetButton, weighted())
        buttons.addView(makeButton("Reload") {
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
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // One listener: remember the status bar height for the Inset experiment and keep
        // the native controls above the navigation gesture area. Insets are not consumed.
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            statusTop = bars.top
            controls.setPadding(dp(12), dp(8), dp(12), bars.bottom + dp(8))
            if (insetOn) applyInsets()
            insets
        }

        setContentView(root)
        ViewCompat.requestApplyInsets(root)

        session = GeckoSession()
        session.setContentDelegate(object : GeckoSession.ContentDelegate {})
        session.open(runtime(this))
        surfaceView.attach(session)
        applyInsets()
        loadTestPage(COVER_URL, "viewport-fit=cover")
    }

    private fun applyInsets() {
        val top = if (insetOn) statusTop else 0
        surfaceView.setSafeInsets(top, 0, 0, 0)
        val css = top / resources.displayMetrics.density
        insetButton.text = if (insetOn) "Inset: on" else "Inset: off"
        stateLabel.text = "GeckoView 159 · $pageLabel · inset ${top}px (≈${"%.1f".format(css)} css)"
    }

    private fun makeButton(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 12f
            isAllCaps = false
            setOnClickListener { action() }
        }

    private fun weighted() = LinearLayout.LayoutParams(
        0,
        LinearLayout.LayoutParams.MATCH_PARENT,
        1f
    ).apply {
        marginStart = dp(3)
        marginEnd = dp(3)
    }

    private fun loadTestPage(url: String, label: String) {
        if (!::session.isInitialized) return
        pageLabel = label
        applyInsets()
        session.loadUri(url)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onResume() {
        super.onResume()
        if (::session.isInitialized) {
            session.setActive(true)
            session.setFocused(true)
        }
    }

    override fun onPause() {
        if (::session.isInitialized) {
            session.setFocused(false)
            session.setActive(false)
        }
        super.onPause()
    }

    override fun onDestroy() {
        if (::surfaceView.isInitialized) surfaceView.detach()
        if (::session.isInitialized && session.isOpen) session.close()
        super.onDestroy()
    }
}
