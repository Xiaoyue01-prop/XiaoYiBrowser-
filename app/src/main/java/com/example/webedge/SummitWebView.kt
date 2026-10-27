package com.example.webedge

import android.content.Context
import android.view.WindowInsets
import android.webkit.WebView
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat

@Suppress("DEPRECATION")
fun trimTopInsetsCompat(insets: WindowInsetsCompat): WindowInsetsCompat {
    val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
    val sbi = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())
    return WindowInsetsCompat.Builder(insets)
        .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(sb.left, 0, sb.right, sb.bottom))
        .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.NONE)
        .setInsetsIgnoringVisibility(
            WindowInsetsCompat.Type.systemBars(), Insets.of(sbi.left, 0, sbi.right, sbi.bottom)
        )
        .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars(), Insets.NONE)
        .setSystemWindowInsets(
            Insets.of(insets.systemWindowInsetLeft, 0, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
        )
        .setStableInsets(
            Insets.of(insets.stableInsetLeft, 0, insets.stableInsetRight, insets.stableInsetBottom)
        )
        .setDisplayCutout(null)
        .build()
}

/** WebView luon nam duoi thanh trang thai, nen khong bao gio de no thay inset phia tren. */
class SummitWebView(context: Context) : WebView(context) {

    private fun trim(raw: WindowInsets): WindowInsets {
        // Khong dung toWindowInsetsCompat(raw, view): no goi lai getRootWindowInsets -> de quy vo han
        return trimTopInsetsCompat(WindowInsetsCompat.toWindowInsetsCompat(raw)).toWindowInsets() ?: raw
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets =
        super.onApplyWindowInsets(trim(insets))

    override fun dispatchApplyWindowInsets(insets: WindowInsets): WindowInsets =
        super.dispatchApplyWindowInsets(trim(insets))

    override fun getRootWindowInsets(): WindowInsets? {
        val raw = super.getRootWindowInsets() ?: return null
        return trim(raw)
    }
}
