package com.example.webedge

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.dynamicanimation.animation.DynamicAnimation
import java.text.DecimalFormat
import java.util.Date

/** Giao diện Downloads và Privacy Dashboard được mở từ MenuController. */
class BrowserToolsController(
    private val activity: Activity,
    private val store: Store,
    private val webView: () -> WebView,
    private val currentUrl: () -> String,
    private val density: Float,
    private val isNight: Boolean,
    private val foreground: Int,
    private val accent: Int,
    private val toast: (String) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private fun dp(value: Int): Int = (value * density).toInt()
    private val muted: Int get() = if (isNight) Color.rgb(174, 174, 180) else Color.rgb(105, 105, 112)
    private val cardColor: Int get() = if (isNight) Color.rgb(43, 43, 43) else Color.rgb(247, 247, 249)
    private val sheet = BrowserBottomSheet(activity, density, isNight, foreground, accent)

    private data class DownloadItem(
        val id: Long,
        val title: String,
        val mime: String,
        val uri: String,
        val status: Int,
        val downloaded: Long,
        val total: Long,
        val reason: Int,
        val modifiedAt: Long
    )

    private data class DownloadRowViews(
        val card: LinearLayout,
        val title: TextView,
        val status: TextView,
        val progress: ProgressBar,
        val size: TextView,
        val actions: LinearLayout,
        var actionType: String = ""
    )

    private val downloadRows = mutableMapOf<Long, DownloadRowViews>()
    private var downloadEmptyView: View? = null

    fun showDownloads() {
        val page = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(18))
        }
        val summaryCard = card()
        val summary = text("Đang tải 0 tệp", 15f, foreground, true)
        val summaryDetail = text("Các tệp tải bằng Summit sẽ xuất hiện tại đây.", 12f, muted)
        summaryCard.addView(summary)
        summaryCard.addView(summaryDetail, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        // Thẻ tổng quan nằm trong vùng cuộn để cuộn xuống dưới tiêu đề cùng danh sách.
        val scroll = ScrollView(activity).apply { clipToPadding = false }
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val scrollBody = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        (summaryCard.parent as? ViewGroup)?.removeView(summaryCard)
        scrollBody.addView(summaryCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        scrollBody.addView(list)
        scroll.addView(scrollBody)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        page.addView(
            text("Android DownloadManager không cung cấp nút tạm dừng/tiếp tục trực tiếp. Summit có thể theo dõi tiến trình, hủy tải, mở hoặc xóa tệp.", 11f, muted)
                .apply { setPadding(dp(4), dp(10), dp(4), dp(2)) }
        )

        downloadRows.clear()
        downloadEmptyView = null
        val refresh = object : Runnable {
            override fun run() {
                if (!sheet.isShowing) return
                val items = queryDownloads()
                val active = items.count { isActive(it.status) }
                summary.text = "Đang tải $active tệp"
                summaryDetail.text = if (items.isEmpty()) {
                    "Chưa có tệp tải xuống nào trong Summit."
                } else {
                    "${items.size} mục trong danh sách · cập nhật tự động"
                }
                renderDownloadItems(list, items)
                handler.postDelayed(this, DOWNLOAD_REFRESH_MS)
            }
        }

        sheet.show("Tải xuống", page) {
            handler.removeCallbacks(refresh)
            downloadRows.clear()
            downloadEmptyView = null
        }
        handler.post(refresh)
    }

    private fun queryDownloads(): List<DownloadItem> {
        val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val items = mutableListOf<DownloadItem>()
        runCatching { manager.query(DownloadManager.Query()) }.getOrNull()?.use { cursor ->
            val idCol = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
            val titleCol = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE)
            val mimeCol = cursor.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE)
            val uriCol = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
            val statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val downloadedCol = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val totalCol = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val reasonCol = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
            val modifiedCol = cursor.getColumnIndex(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP)
            while (cursor.moveToNext()) {
                val id = if (idCol >= 0) cursor.getLong(idCol) else continue
                items += DownloadItem(
                    id = id,
                    title = if (titleCol >= 0) cursor.getString(titleCol).orEmpty().ifBlank { "Tệp tải xuống" } else "Tệp tải xuống",
                    mime = if (mimeCol >= 0) cursor.getString(mimeCol).orEmpty() else "",
                    uri = if (uriCol >= 0) cursor.getString(uriCol).orEmpty() else "",
                    status = if (statusCol >= 0) cursor.getInt(statusCol) else -1,
                    downloaded = if (downloadedCol >= 0) cursor.getLong(downloadedCol) else 0L,
                    total = if (totalCol >= 0) cursor.getLong(totalCol) else -1L,
                    reason = if (reasonCol >= 0) cursor.getInt(reasonCol) else 0,
                    modifiedAt = if (modifiedCol >= 0) cursor.getLong(modifiedCol) else 0L
                )
            }
        }
        return items.sortedByDescending { it.modifiedAt }
    }

    private fun renderDownloadItems(container: LinearLayout, items: List<DownloadItem>) {
        if (items.isEmpty()) {
            if (downloadRows.isEmpty() && downloadEmptyView != null) return
            container.removeAllViews()
            downloadRows.clear()
            val empty = card()
            empty.addView(text("Chưa có bản tải xuống", 15f, foreground, true))
            empty.addView(
                text("Khi bồ tải file từ website, tiến trình và tệp hoàn tất sẽ được hiển thị ở đây.", 13f, muted),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
            )
            container.addView(empty)
            downloadEmptyView = empty
            return
        }

        downloadEmptyView?.let {
            container.removeView(it)
            downloadEmptyView = null
        }
        val visibleItems = items.take(MAX_DOWNLOAD_ROWS)
        val visibleIds = visibleItems.mapTo(mutableSetOf()) { it.id }
        downloadRows.keys.filter { it !in visibleIds }.toList().forEach { id ->
            downloadRows.remove(id)?.let { container.removeView(it.card) }
        }

        visibleItems.forEachIndexed { index, item ->
            val row = downloadRows.getOrPut(item.id) { createDownloadRow(item) }
            bindDownloadRow(row, item)
            val currentIndex = container.indexOfChild(row.card)
            if (currentIndex != index) {
                (row.card.parent as? ViewGroup)?.removeView(row.card)
                container.addView(row.card, index.coerceAtMost(container.childCount))
            }
        }
    }

    private fun createDownloadRow(item: DownloadItem): DownloadRowViews {
        val itemCard = card()
        val title = text(item.title, 14f, foreground, true).apply { maxLines = 2 }
        itemCard.addView(title)
        val status = text("", 12f, muted).apply { setPadding(0, dp(4), 0, 0) }
        itemCard.addView(status)
        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(
                if (isNight) Color.rgb(74, 74, 78) else Color.rgb(225, 225, 230)
            )
        }
        itemCard.addView(progress, LinearLayout.LayoutParams(-1, dp(5)).apply { topMargin = dp(10) })
        val size = text("", 11f, muted).apply { setPadding(0, dp(5), 0, 0) }
        itemCard.addView(size)
        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        itemCard.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        return DownloadRowViews(itemCard, title, status, progress, size, actions)
    }

    private fun bindDownloadRow(row: DownloadRowViews, item: DownloadItem) {
        row.title.text = item.title
        row.status.text = downloadStatus(item)
        val active = isActive(item.status)
        row.progress.visibility = if (active) View.VISIBLE else View.GONE
        row.progress.progress = if (item.total > 0L) {
            ((item.downloaded * 100L) / item.total).toInt().coerceIn(0, 100)
        } else 0
        row.size.visibility = if (active || item.status == DownloadManager.STATUS_SUCCESSFUL) View.VISIBLE else View.GONE
        row.size.text = if (item.total > 0L) {
            "${formatBytes(item.downloaded)} / ${formatBytes(item.total)}"
        } else formatBytes(item.downloaded)

        val actionType = when {
            active -> "active"
            item.status == DownloadManager.STATUS_SUCCESSFUL -> "complete"
            else -> "other"
        }
        if (row.actionType != actionType) {
            row.actions.removeAllViews()
            if (actionType == "complete") {
                row.actions.addView(actionButton("Mở", accent) { openDownload(item) },
                    LinearLayout.LayoutParams(-2, dp(38)).apply { marginEnd = dp(6) })
                row.actions.addView(actionButton("Chia sẻ", muted) { shareDownload(item) },
                    LinearLayout.LayoutParams(-2, dp(38)).apply { marginEnd = dp(6) })
            }
            val removeLabel = if (active) "Hủy tải" else "Xóa"
            row.actions.addView(actionButton(removeLabel, if (active) muted else Theme.DANGER_RED) {
                confirmRemove(item)
            }, LinearLayout.LayoutParams(-2, dp(38)))
            row.actionType = actionType
        }
    }

    private fun isActive(status: Int): Boolean = status == DownloadManager.STATUS_PENDING ||
        status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PAUSED

    private fun downloadStatus(item: DownloadItem): String = when (item.status) {
        DownloadManager.STATUS_PENDING -> "Đang chờ"
        DownloadManager.STATUS_RUNNING -> {
            val percent = if (item.total > 0L) ((item.downloaded * 100L) / item.total).toInt().coerceIn(0, 100) else null
            if (percent == null) "Đang tải xuống" else "Đang tải xuống · $percent%"
        }
        DownloadManager.STATUS_PAUSED -> "Tạm dừng · ${pauseReason(item.reason)}"
        DownloadManager.STATUS_SUCCESSFUL -> "Đã tải xong · ${formatDate(item.modifiedAt)}"
        DownloadManager.STATUS_FAILED -> "Tải thất bại · mã ${item.reason}"
        else -> "Không xác định"
    }

    private fun pauseReason(reason: Int): String = when (reason) {
        DownloadManager.PAUSED_WAITING_TO_RETRY -> "đang thử lại"
        DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "chờ mạng"
        DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "chờ Wi-Fi"
        DownloadManager.PAUSED_UNKNOWN -> "hệ thống tạm dừng"
        else -> "mã $reason"
    }

    private fun openDownload(item: DownloadItem) {
        val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = runCatching { manager.getUriForDownloadedFile(item.id) }.getOrNull()
            ?: item.uri.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
        if (uri == null) {
            toast("Không tìm thấy tệp đã tải")
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, item.mime.ifBlank { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { activity.startActivity(Intent.createChooser(intent, "Mở tệp")) }
            .onFailure { toast("Không tìm thấy ứng dụng mở loại tệp này") }
    }

    private fun shareDownload(item: DownloadItem) {
        val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = runCatching { manager.getUriForDownloadedFile(item.id) }.getOrNull()
            ?: item.uri.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
        if (uri == null) {
            toast("Không tìm thấy tệp để chia sẻ")
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = item.mime.ifBlank { "*/*" }
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { activity.startActivity(Intent.createChooser(intent, "Chia sẻ tệp")) }
            .onFailure { toast("Không thể chia sẻ tệp này") }
    }

    private fun confirmRemove(item: DownloadItem) {
        val active = isActive(item.status)
        sheet.confirm(
            title = if (active) "Hủy tải xuống?" else "Xóa tệp tải xuống?",
            message = if (active) "Summit sẽ hủy tiến trình tải ${item.title}." else "Tệp ${item.title} sẽ bị xóa khỏi danh sách tải xuống và thiết bị.",
            positive = if (active) "Hủy tải" else "Xóa",
            negative = "Giữ lại"
        ) {
            if (active) {
                DownloadLiveUpdate.cancel(activity, item.id, item.title)
            } else {
                val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                manager.remove(item.id)
            }
            toast(if (active) "Đã hủy tải xuống" else "Đã xóa tệp tải xuống")
        }
    }

    fun showPrivacy() {
        val page = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(22))
        }
        val scroll = ScrollView(activity).apply { clipToPadding = false }
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        renderPrivacy(body)

        val refreshStats = object : Runnable {
            override fun run() {
                if (!sheet.isShowing) return
                val stats = AdBlocker.stats()
                blockedSummary?.text = "${stats.blockedRequests} yêu cầu bị chặn · ${stats.uniqueHosts} máy chủ"
                handler.postDelayed(this, PRIVACY_REFRESH_MS)
            }
        }
        sheet.show("Privacy Dashboard", page) { handler.removeCallbacks(refreshStats); blockedSummary = null }
        handler.post(refreshStats)
    }

    private var blockedSummary: TextView? = null

    private fun renderPrivacy(body: LinearLayout) {
        body.removeAllViews()
        val stats = AdBlocker.stats()
        val statsCard = card()
        statsCard.addView(text("Bảo vệ trong phiên này", 14f, foreground, true))
        blockedSummary = text(
            "${stats.blockedRequests} yêu cầu bị chặn · ${stats.uniqueHosts} máy chủ",
            13f, muted
        ).apply { setPadding(0, dp(5), 0, 0) }
        statsCard.addView(blockedSummary)
        val resetStats = actionButton("Đặt lại thống kê", muted) {
            AdBlocker.resetStats()
            blockedSummary?.text = "0 yêu cầu bị chặn · 0 máy chủ"
        }
        statsCard.addView(resetStats, LinearLayout.LayoutParams(-2, dp(36)).apply { topMargin = dp(10) })
        body.addView(statsCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        val filterCard = card()
        val filterRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val filterTexts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        filterTexts.addView(text("Chặn quảng cáo theo danh sách", 14f, foreground, true))
        filterTexts.addView(text("Chỉ đếm các request bị bộ lọc Summit chặn thực tế.", 11f, muted)
            .apply { setPadding(0, dp(3), 0, 0) })
        filterRow.addView(filterTexts, LinearLayout.LayoutParams(0, -2, 1f))
        val enabled = Switch(activity).apply {
            isChecked = store.adBlockEnabled
            setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
                store.adBlockEnabled = checked
                val js = if (checked) AdBlocker.HIDE_CSS
                    else "document.getElementById('__adblock_hide')?.remove();"
                runCatching { webView().evaluateJavascript(js, null) }
                toast(if (checked) "Đã bật chặn quảng cáo" else "Đã tắt chặn quảng cáo")
            }
        }
        filterRow.addView(enabled)
        filterCard.addView(filterRow)
        body.addView(filterCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })

        addSectionTitle(body, "Quyền của website hiện tại")
        val origin = originFrom(currentUrl())
        if (origin == null) {
            val noSite = card()
            noSite.addView(text("Chưa có website hợp lệ để quản lý quyền.", 13f, muted))
            body.addView(noSite, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        } else {
            val currentCard = card()
            currentCard.addView(text(displayOrigin(origin), 13f, foreground, true))
            currentCard.addView(
                text("Cài đặt bên dưới áp dụng cho origin hiện tại. Quyền hệ thống Android vẫn được kiểm tra riêng.", 11f, muted)
                    .apply { setPadding(0, dp(4), 0, dp(8)) }
            )
            addPermissionControl(currentCard, "Camera và microphone", store.getMediaPermission(origin)) {
                store.setMediaPermission(origin, it)
            }
            addPermissionControl(currentCard, "Vị trí", store.getGeoPermission(origin)) {
                store.setGeoPermission(origin, it)
            }
            body.addView(currentCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        }

        addSectionTitle(body, "Quyền đã lưu cho website khác")
        val otherPermissions = store.sitePermissionEntries().filter { it.origin != origin }
        if (otherPermissions.isEmpty()) {
            val empty = card()
            empty.addView(text("Chưa có quyền nào được lưu cho website khác.", 12f, muted))
            body.addView(empty, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        } else {
            otherPermissions.take(MAX_PERMISSION_ORIGINS).forEach { entry ->
                val savedCard = card()
                savedCard.addView(text(displayOrigin(entry.origin), 13f, foreground, true))
                val summary = buildList {
                    if (entry.media != PermissionState.ASK) add("Camera/mic: ${permissionLabel(entry.media)}")
                    if (entry.location != PermissionState.ASK) add("Vị trí: ${permissionLabel(entry.location)}")
                }.joinToString(" · ").ifBlank { "Không có quyền đã lưu" }
                savedCard.addView(text(summary, 11f, muted).apply { setPadding(0, dp(4), 0, 0) })
                savedCard.addView(actionButton("Đặt lại quyền", muted) {
                    store.clearSitePermissions(entry.origin)
                    renderPrivacy(body)
                }, LinearLayout.LayoutParams(-2, dp(36)).apply { topMargin = dp(8) })
                body.addView(savedCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            }
            if (otherPermissions.size > MAX_PERMISSION_ORIGINS) {
                body.addView(text("Còn ${otherPermissions.size - MAX_PERMISSION_ORIGINS} website khác.", 11f, muted))
            }
            body.addView(actionButton("Đặt lại tất cả quyền đã lưu", Theme.DANGER_RED) {
                sheet.confirm(
                    title = "Đặt lại mọi quyền website?",
                    message = "Tất cả lựa chọn cho phép/từ chối của website sẽ trở về trạng thái hỏi lại.",
                    positive = "Đặt lại"
                ) {
                    store.clearSitePermissions(null)
                    renderPrivacy(body)
                }
            }, LinearLayout.LayoutParams(-2, dp(38)).apply { topMargin = dp(8); bottomMargin = dp(12) })
        }

        addSectionTitle(body, "Dữ liệu website")
        val dataCard = card()
        dataCard.addView(text("Xóa bộ nhớ website hiện tại", 13f, foreground, true))
        dataCard.addView(text("Xóa local storage và dữ liệu Web Storage của origin đang mở.", 11f, muted)
            .apply { setPadding(0, dp(4), 0, dp(8)) })
        dataCard.addView(actionButton("Xóa dữ liệu website", Theme.DANGER_RED) {
            val site = originFrom(currentUrl())
            if (site == null) {
                toast("Không xác định được website hiện tại")
            } else {
                sheet.confirm(
                    title = "Xóa dữ liệu website?",
                    message = "Dữ liệu Web Storage của ${displayOrigin(site)} sẽ bị xóa. Cookie không bị xóa bởi thao tác này.",
                    positive = "Xóa"
                ) {
                    runCatching { WebStorage.getInstance().deleteOrigin(site) }
                    toast("Đã xóa dữ liệu Web Storage của website")
                }
            }
        }, LinearLayout.LayoutParams(-2, dp(38)))
        val divider = View(activity).apply {
            setBackgroundColor(if (isNight) Color.rgb(70, 70, 70) else Color.rgb(225, 225, 230))
        }
        dataCard.addView(divider, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(12); bottomMargin = dp(10) })
        dataCard.addView(text("Cookie và cache", 13f, foreground, true))
        dataCard.addView(text("Cookie và cache là dữ liệu dùng chung giữa các website trong WebView.", 11f, muted)
            .apply { setPadding(0, dp(4), 0, dp(8)) })
        val globalActions = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        globalActions.addView(actionButton("Xóa cookie", Theme.DANGER_RED) {
            sheet.confirm(
                title = "Xóa tất cả cookie?",
                message = "Thao tác này đăng xuất các website và áp dụng cho mọi website trong Summit.",
                positive = "Xóa cookie"
            ) {
                CookieManager.getInstance().removeAllCookies {
                    CookieManager.getInstance().flush()
                    toast("Đã xóa cookie của Summit")
                }
            }
        }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(8) })
        globalActions.addView(actionButton("Xóa cache", muted) {
            sheet.confirm(
                title = "Xóa cache trình duyệt?",
                message = "Cache tài nguyên của tất cả website trong Summit sẽ bị xóa. Cookie và dữ liệu đăng nhập không bị ảnh hưởng.",
                positive = "Xóa cache"
            ) {
                runCatching { webView().clearCache(true) }
                toast("Đã xóa cache trình duyệt")
            }
        }, LinearLayout.LayoutParams(0, dp(38), 1f))
        dataCard.addView(globalActions)
        body.addView(dataCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        body.addView(
            text("Thống kê chặn chỉ phản ánh các request mà danh sách domain hiện tại nhận diện được; Summit chưa phân loại riêng toàn bộ tracker.", 11f, muted)
                .apply { setPadding(dp(4), dp(2), dp(4), dp(8)) }
        )
    }

    private fun addPermissionControl(
        parent: LinearLayout,
        label: String,
        initialState: PermissionState,
        onChange: (PermissionState) -> Unit
    ) {
        val group = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(text(label, 12f, foreground, true), LinearLayout.LayoutParams(0, -2, 1f))
        val stateText = text(permissionLabel(initialState), 11f, muted)
        row.addView(stateText)
        group.addView(row)

        // Thanh chọn phân đoạn dùng chung của app (viền kính chung, viên kính trượt): xem GlassSystem.SegmentedControl.
        val choices = listOf(
            "Hỏi lại" to PermissionState.ASK,
            "Cho phép" to PermissionState.ALLOW,
            "Chặn" to PermissionState.DENY
        )
        val control = GlassSystem.SegmentedControl(
            context = activity,
            density = density,
            isNight = isNight,
            labels = choices.map { it.first },
            initialIndex = choices.indexOfFirst { it.second == initialState },
            selectedColor = accent,
            normalColor = muted,
            trackFill = GlassSystem.createSegmentedTrack(24, density, isNight)
        ) { index ->
            val state = choices[index].second
            onChange(state)
            stateText.text = permissionLabel(state)
            toast("Đã cập nhật quyền: $label")
        }
        group.addView(control.view, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(7) })
        group.clipChildren = false
        group.clipToPadding = false
        parent.clipChildren = false
        parent.clipToPadding = false
        parent.addView(group, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }

    private fun originFrom(rawUrl: String): String? = runCatching {
        val uri = Uri.parse(rawUrl)
        val scheme = uri.scheme?.takeIf { it == "http" || it == "https" } ?: return@runCatching null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return@runCatching null
        "$scheme://$host${if (uri.port != -1) ":${uri.port}" else ""}"
    }.getOrNull()

    private fun displayOrigin(origin: String): String =
        runCatching { Uri.parse(origin).host?.removePrefix("www.") }.getOrNull() ?: origin

    private fun permissionLabel(state: PermissionState): String = when (state) {
        PermissionState.ASK -> "Hỏi mỗi lần"
        PermissionState.ALLOW -> "Đã cho phép"
        PermissionState.DENY -> "Đã chặn"
    }

    private fun addSectionTitle(parent: LinearLayout, title: String) {
        parent.addView(text(title, 14f, foreground, true).apply {
            setPadding(dp(3), dp(3), dp(3), dp(8))
        })
    }

    private fun card(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
        setPadding(dp(14), dp(13), dp(14), dp(13))
        background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(cardColor)
        }
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(activity).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

    private fun actionButton(label: String, color: Int, onClick: () -> Unit): TextView =
        TextView(activity).apply {
            text = label
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (color == muted) this@BrowserToolsController.foreground else color)
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(if (isNight) Color.rgb(58, 58, 60) else Color.rgb(238, 238, 242))
                setStroke(dp(1), if (color == muted) {
                    if (isNight) Color.rgb(82, 82, 86) else Color.rgb(218, 218, 224)
                } else color)
            }
            setOnClickListener { onClick() }
            AnimationSystem.press(this, Constants.PRESS_SCALE_PILL)
        }

    private fun formatBytes(value: Long): String {
        if (value < 0L) return "Không rõ dung lượng"
        if (value < 1024L) return "$value B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var amount = value.toDouble()
        var index = -1
        do {
            amount /= 1024.0
            index++
        } while (amount >= 1024.0 && index < units.lastIndex)
        return "${DecimalFormat("#0.0").format(amount)} ${units[index]}"
    }

    private fun formatDate(timestamp: Long): String = if (timestamp <= 0L) "" else
        DateFormat.format("dd/MM · HH:mm", Date(timestamp)).toString()

    private companion object {
        const val DOWNLOAD_REFRESH_MS = 1100L
        const val PRIVACY_REFRESH_MS = 1400L
        const val MAX_DOWNLOAD_ROWS = 40
        const val MAX_PERMISSION_ORIGINS = 12
    }
}
