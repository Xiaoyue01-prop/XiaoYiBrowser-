package com.example.webedge

import android.app.Activity
import android.app.Dialog
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.summitgecko.GeckoSurfaceView
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings

class GeckoPopupWindowController(
    private val activity: Activity,
    private val getRuntime: () -> GeckoRuntime,
    private val openExternal: (String, GeckoSession) -> Boolean,
    private val toast: (String) -> Unit,
    private val isNight: () -> Boolean = { GlassSystem.isDarkPage() }
) {
    var session: GeckoSession? = null
        private set
    private var surfaceView: GeckoSurfaceView? = null
    private var dialog: Dialog? = null
    private var sheet: View? = null
    private var scrim: View? = null
    private var titleView: TextView? = null
    private var closing = false
    private var currentUrl: String? = null
    private var canGoBack = false

    val isShowing: Boolean get() = dialog?.isShowing == true

    fun create(uri: String, sourceSession: GeckoSession): GeckoSession? {
        if (session != null) return null
        
        val night = isNight()
        val sheetColor = GlassSystem.pageSurfaceColor()
        val textColor = GlassSystem.pagePrimaryText()
        val grabberColor = GlassSystem.pageSheetGrabberColor()
        val buttonColor = GlassSystem.pageSheetButtonColor()

        val root = FrameLayout(activity)
        val scrimView = View(activity).apply {
            setBackgroundColor(GlassSystem.pageScrimColor())
            alpha = 0f
            setOnClickListener { animateClose() }
        }
        root.addView(scrimView, FrameLayout.LayoutParams(-1, -1))

        val corner = dp(36)
        val sheetView = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = ColorDrawable(sheetColor)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height + corner, corner.toFloat())
                }
            }
            clipToOutline = true
            isClickable = true
            translationY = activity.resources.displayMetrics.heightPixels.toFloat()
        }
        val sheetLp = FrameLayout.LayoutParams(-1, -1)
        root.addView(sheetView, sheetLp)

        val dragZone = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val grabber = View(activity).apply {
            background = roundedDrawable(dp(3).toFloat(), grabberColor)
        }
        dragZone.addView(
            grabber,
            LinearLayout.LayoutParams(dp(36), dp(5)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(8)
            }
        )
        val header = FrameLayout(activity)
        val closeBtn = ImageView(activity).apply {
            setImageDrawable(PathIcon(Icons.CLOSE, textColor))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = ovalDrawable(buttonColor)
            contentDescription = "Đóng"
            setOnClickListener { animateClose() }
        }
        AnimationSystem.press(closeBtn, Constants.PRESS_SCALE_BTN)
        header.addView(
            closeBtn,
            FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER_VERTICAL or Gravity.START).apply {
                marginStart = dp(16)
            }
        )
        val title = TextView(activity).apply {
            text = "Cửa sổ mới"
            textSize = 17f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(textColor)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        titleView = title
        header.addView(
            title,
            FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply {
                marginStart = dp(72)
                marginEnd = dp(72)
            }
        )
        dragZone.addView(header, LinearLayout.LayoutParams(-1, dp(56)))

        val pageHost = FrameLayout(activity)
        
        val isPrivate = sourceSession.settings.usePrivateMode
        val settings = GeckoSessionSettings.Builder()
            .usePrivateMode(isPrivate)
            .build()
        val newSession = GeckoSession(settings)
        
        newSession.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                val clean = title?.trim().orEmpty().take(180)
                val host = runCatching { Uri.parse(currentUrl ?: "").host }.getOrNull()?.removePrefix("www.")
                activity.runOnUiThread {
                    titleView?.text = clean.ifBlank { host ?: "Cửa sổ mới" }
                }
            }
            override fun onCloseRequest(session: GeckoSession) {
                activity.runOnUiThread { animateClose() }
            }
        }
        
        newSession.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                this@GeckoPopupWindowController.canGoBack = canGoBack
            }
            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<GeckoSession.NavigationDelegate.AllowOrDeny>? {
                if (request.isRedirect) return null
                return if (openExternal(request.uri, session))
                    GeckoResult.fromValue(GeckoSession.NavigationDelegate.AllowOrDeny.DENY) else null
            }

            override fun onLocationChange(session: GeckoSession, url: String?, permissions: MutableList<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) {
                currentUrl = url
                if (titleView?.text == "Cửa sổ mới" && !url.isNullOrBlank()) {
                    val host = runCatching { Uri.parse(url).host }.getOrNull()?.removePrefix("www.")
                    activity.runOnUiThread { titleView?.text = host ?: url }
                }
            }
        }
        
        val sv = GeckoSurfaceView(activity)
        pageHost.addView(sv, FrameLayout.LayoutParams(-1, -1))
        pageHost.addView(dragZone, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        
        sheetView.addView(pageHost, LinearLayout.LayoutParams(-1, -1))
        
        BrowserSheetMotion.bindDragZone(dragZone, sheetView, scrimView, ::dp, ::animateClose)
        
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            sheetLp.topMargin = bars.top + dp(BrowserSheetMotion.TOP_MARGIN_DP)
            sheetView.layoutParams = sheetLp
            sheetView.setPadding(0, 0, 0, ime.bottom)
            insets
        }
        
        val dlg = object : Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar) {
            @Deprecated("Deprecated in Java")
            override fun onBackPressed() {
                if (canGoBack) newSession.goBack() else animateClose()
            }
        }
        dlg.setContentView(root)
        dlg.window?.let { BrowserSheetMotion.configureWindow(it, root, night) }
        dlg.setOnDismissListener {
            GlassSystem.removePageToneObserver(root)
            if (session === newSession) {
                sv.detach()
                (sv.parent as? ViewGroup)?.removeView(sv)
                runCatching { if (newSession.isOpen) newSession.close() }
                session = null
                surfaceView = null
                dialog = null
                sheet = null
                scrim = null
                closing = false
            }
        }
        
        session = newSession
        surfaceView = sv
        dialog = dlg
        sheet = sheetView
        scrim = scrimView
        closing = false
        
        dlg.show()
        GlassSystem.bindForegroundRoot(root)
        GlassSystem.observePageTone(root) { color ->
            val dark = GlassSystem.isDarkPage(color)
            val foregroundColor = GlassSystem.pagePrimaryText(color)
            sheetView.background = ColorDrawable(GlassSystem.pageSurfaceColor(color))
            title.setTextColor(foregroundColor)
            closeBtn.setImageDrawable(PathIcon(Icons.CLOSE, foregroundColor))
            closeBtn.background = ovalDrawable(GlassSystem.pageSheetButtonColor(color))
            (grabber.background as? GradientDrawable)?.setColor(GlassSystem.pageSheetGrabberColor(color))
            dlg.window?.let { BrowserSheetMotion.configureWindow(it, root, dark) }
            sheetView.invalidate()
        }
        
        dlg.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        ViewCompat.requestApplyInsets(root)
        
        root.post {
            if (closing) return@post
            ViewCompat.requestApplyInsets(root)
            BrowserSheetMotion.show(sheetView, scrimView)
            
            sv.post {
                if (closing) return@post
                sv.attach(newSession)
                newSession.open(getRuntime())
                newSession.loadUri(uri)
            }
        }
        
        return newSession
    }

    private fun animateClose(velocity: Float = 0f) {
        val dlg = dialog ?: return
        if (closing) return
        closing = true
        BrowserSheetMotion.close(dlg, sheet, scrim, ::dp, velocity)
    }

    fun close(immediate: Boolean = false) {
        if (immediate) dialog?.dismiss() else animateClose()
    }
    
    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density + 0.5f).toInt()
    private fun roundedDrawable(radiusPx: Float, color: Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = radiusPx
        setColor(color)
    }
    private fun ovalDrawable(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }
}
