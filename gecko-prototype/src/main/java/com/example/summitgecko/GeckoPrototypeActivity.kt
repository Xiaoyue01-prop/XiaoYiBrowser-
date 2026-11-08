package com.example.summitgecko

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.ViewCompat
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

/**
 * Isolated engine spike. It deliberately does not touch Summit's production WebView path.
 * The only purpose of this APK is to verify Gecko rendering and edge-to-edge behavior
 * against pages with and without viewport-fit=cover.
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

    private lateinit var geckoView: GeckoView
    private lateinit var session: GeckoSession
    private lateinit var stateLabel: TextView

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

        geckoView = GeckoView(this).apply {
            setBackgroundColor(Color.WHITE)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        root.addView(
            geckoView,
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
            text = "GeckoView 159 · Edge-to-edge test"
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

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        buttons.addView(makeButton("Cover") { loadTestPage(COVER_URL, "viewport-fit=cover") }, weighted())
        buttons.addView(makeButton("No cover") { loadTestPage(DEFAULT_URL, "default viewport") }, weighted())
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

        // Keep the native test controls above the navigation gesture area.
        // GeckoView itself remains edge-to-edge and receives the full content surface.
        ViewCompat.setOnApplyWindowInsetsListener(controls) { view, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(dp(12), dp(8), dp(12), nav.bottom + dp(8))
            insets
        }

        setContentView(root)
        ViewCompat.requestApplyInsets(root)

        session = GeckoSession()
        session.setContentDelegate(object : GeckoSession.ContentDelegate {})
        session.open(runtime(this))
        geckoView.setSession(session)
        loadTestPage(COVER_URL, "viewport-fit=cover")
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
        stateLabel.text = "GeckoView 159 · $label"
        session.loadUri(url)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onResume() {
        super.onResume()
        if (::session.isInitialized) session.setActive(true)
    }

    override fun onPause() {
        if (::session.isInitialized) session.setActive(false)
        super.onPause()
    }

    override fun onDestroy() {
        if (::geckoView.isInitialized) {
            val attached = geckoView.releaseSession()
            if (attached != null && attached.isOpen) attached.close()
        }
        super.onDestroy()
    }
}
