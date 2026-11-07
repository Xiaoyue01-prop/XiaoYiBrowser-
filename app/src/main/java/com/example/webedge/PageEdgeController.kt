package com.example.webedge

import android.content.Context
import android.view.View
import android.webkit.WebView

/** Trạng thái của Activity mà [PageEdgeController] cần đọc (chỉ đọc, không sửa). */
interface PageEdgeHost {
    val density: Float
    val insetTop: Int
    val insetBottom: Int
    val imeBottom: Int
    val barHidden: Boolean
    val barPresent: Boolean
    /** Trang đang xem tràn viền (khai báo viewport-fit=cover); WebView nằm dưới thanh trạng thái thì là false. */
    val isCover: Boolean
    val isDestroyed: Boolean
    val isFullscreenVideoShowing: Boolean
    /** Thanh địa chỉ nổi đã được dựng chưa. */
    val hasBar: Boolean
    /** WebView của tab đang xem; null nếu chưa khởi tạo. */
    val web: WebView?
    /** Khung viên thuốc địa chỉ; null nếu chưa khởi tạo. */
    val pillFrame: View?
}

/**
 * Chừa chỗ cuối trang cho viên thuốc địa chỉ và chạy script lấy màu mép trên / nâng thanh đáy của website.
 * Phía trên không đệm gì: WebView hoặc nằm dưới thanh trạng thái, hoặc tràn viền và trang tự lo bằng env().
 * Script gốc nằm ở assets/edge_script.js (xem [EdgeScript]).
 */
class PageEdgeController(
    private val context: Context,
    private val host: PageEdgeHost,
    private val bottomBarHeightDp: Int
) {
    // Chừa chỗ cuối trang cho viên thuốc và chạy script lấy màu / nâng thanh đáy
    fun injectInsets() {
        val target = host.web ?: return
        val d = host.density
        val bottomCss = (host.insetBottom / d).toInt() + 64
        val js = "(function(){var s=document.getElementById('__edge');" +
            "if(!s){s=document.createElement('style');s.id='__edge';" +
            "(document.head||document.documentElement).appendChild(s);}" +
            "s.textContent='html{padding-bottom:" + bottomCss + "px!important;box-sizing:border-box!important}';})();"
        target.evaluateJavascript(js, null)
        val cover = host.isCover.toString()
        val bottom = bottomReserveCss()
        // Trang đã nạp script rồi thì chỉ cập nhật vài con số; chỉ gửi cả đoạn script dài khi trang chưa có.
        target.evaluateJavascript(
            "(function(){if(!window.__edgeStarted)return 0;window.__edgeCover=" + cover +
                ";window.__edgeBottom=" + bottom +
                ";window.__edgeCompact=" + shouldHideWebsiteBottomNav() + ";" + compactPillMetricsJs() +
                "if(window.__edgeRun)window.__edgeRun();return 1})()"
        ) { result ->
            if (result != "1" && !host.isDestroyed) {
                target.evaluateJavascript(
                    EdgeScript.template(context).replace("%COVER%", cover)
                        .replace("%BOTTOM%", bottom.toString())
                        .replace("%COMPACT%", shouldHideWebsiteBottomNav().toString())
                        .replace("%PILL_METRICS%", compactPillMetricsJs()),
                    null
                )
            }
        }
    }

    // Chỉ ẩn thanh điều hướng của website khi viên thuốc thực sự ở trạng thái thu gọn.
    // Khi bàn phím, FindBar hoặc video toàn màn hình chiếm UI thì không giữ trạng thái compact cũ.
    private fun shouldHideWebsiteBottomNav(): Boolean =
        host.barHidden && host.barPresent && !host.isFullscreenVideoShowing

    // Khoảng (px CSS) từ mép dưới WebView lên tới đỉnh thanh địa chỉ nổi. Các thanh cố định ở đáy trang
    // được nâng lên đúng khoảng này để không bị thanh địa chỉ đè lên, giống Safari.
    private fun bottomReserveCss(): Int {
        if (!host.hasBar || !host.barPresent || host.isFullscreenVideoShowing) return 0
        val half = dp(bottomBarHeightDp) / 2f
        val barTop = maxOf(host.insetBottom, host.imeBottom) + dp(10) + half +
            half * (if (host.barHidden) 0.62f else 1f) + dp(6)
        return (maxOf(0f, barTop - host.imeBottom) / host.density).toInt()
    }

    // Tọa độ và kích thước đích của viên thuốc ở trạng thái thu gọn, tính trong hệ tọa độ CSS của WebView.
    // Dùng cùng một đích cho cả hai chiều để thanh website co đúng vào pill rồi bung ra từ chính vị trí đó.
    private fun compactPillMetricsJs(): String {
        val web = host.web
        val pill = host.pillFrame
        val d = host.density
        if (web == null || pill == null ||
            web.width <= 0 || web.height <= 0 || pill.width <= 0 || pill.height <= 0 || d <= 0f
        ) {
            return "window.__edgePillX=(window.innerWidth||0)/2;window.__edgePillY=(window.innerHeight||0)-34;" +
                "window.__edgePillW=(window.innerWidth||0)*0.40;window.__edgePillH=25;"
        }
        val webPos = IntArray(2)
        val pillPos = IntArray(2)
        web.getLocationOnScreen(webPos)
        pill.getLocationOnScreen(pillPos)
        val x = (pillPos[0] + pill.width / 2f - webPos[0]) / d
        val y = (pillPos[1] + pill.height / 2f - webPos[1]) / d
        val width = pill.width * 0.58f / d
        val height = pill.height * 0.62f / d
        return "window.__edgePillX=$x;window.__edgePillY=$y;" +
            "window.__edgePillW=$width;window.__edgePillH=$height;"
    }

    fun pushBottomReserve() {
        val web = host.web ?: return
        web.evaluateJavascript(
            "window.__edgeBottom=" + bottomReserveCss() +
                ";window.__edgeCompact=" + shouldHideWebsiteBottomNav() + ";" + compactPillMetricsJs() +
                "window.__edgeLift&&window.__edgeLift();",
            null
        )
    }
}
