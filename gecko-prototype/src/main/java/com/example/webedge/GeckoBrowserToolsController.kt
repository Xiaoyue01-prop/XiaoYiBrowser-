package com.example.webedge

import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.app.DownloadManager
import android.content.Context
import android.provider.MediaStore
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
import eightbitlab.com.blurview.BlurTarget
import java.text.DecimalFormat
import java.util.Date

internal class GeckoBrowserToolsController(
    private val activity: Activity,
    private val root: FrameLayout,
    private val blurTarget: BlurTarget,
    private val store: Store,
    private val dialog: () -> GlassDialogController,
    private val density: Float,
    private val isNight: () -> Boolean,
    private val foreground: () -> Int,
    private val accent: Int,
    private val adBlockEnabled: () -> Boolean,
    private val onToggleAdBlock: () -> Unit,
    private val adStats: () -> Pair<Int, Int>,
    private val onResetAdStats: () -> Unit,
    private val youtubeAdBlockEnabled: () -> Boolean,
    private val onToggleYoutubeAdBlock: () -> Unit,
    private val isPrivateMode: () -> Boolean,
    private val onClearCookies: () -> Unit,
    private val onClearCache: () -> Unit,
    private val onClearSiteData: () -> Unit,
    private val activeDownloads: () -> List<GeckoDownloadController.ActiveDownload>,
    private val cancelDownload: (Long) -> Unit,
    private val onCancelDownload: (String) -> Unit,
    private val onClearPermissions: () -> Unit,
    private val onToast: (String) -> Unit,
    private val currentUrl: () -> String
) {
    private val handler = Handler(Looper.getMainLooper())
    private val muted: Int get() = if (isNight()) Color.rgb(190, 192, 200) else Color.rgb(105, 105, 112)
    private val cardColor: Int get() = if (isNight()) Color.rgb(43, 45, 52) else Color.rgb(247, 247, 249)
    private val sheet = BrowserBottomSheet(activity, density, isNight, foreground(), accent)
    private val activeToneUpdates = mutableListOf<(Boolean) -> Unit>()

    private fun applyActivePageTone(color: Int?) {
        val dark = GlassSystem.isDarkPage(color)
        activeToneUpdates.toList().forEach { update -> runCatching { update(dark) } }
    }
    private fun clearActiveToneUpdates() { activeToneUpdates.clear() }

    private data class DownloadItem(val id: Long, val title: String, val mime: String, val uri: Uri, val size: Long, val isPending: Boolean, val modifiedAt: Long, val read: Long = 0L, val total: Long = -1L)
    private data class DownloadRowViews(val card: LinearLayout, val title: TextView, val status: TextView, val progress: ProgressBar, val size: TextView, val actions: LinearLayout, var actionType: String = "")
    private val downloadRows = mutableMapOf<Long, DownloadRowViews>()
    private var downloadEmptyView: View? = null

    fun showDownloads() {
        clearActiveToneUpdates()
        val page = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(18)) }
        val summaryCard = card()
        val summary = text("Đang tải 0 tệp", 15f, foreground(), true)
        val summaryDetail = text("Các tệp tải bằng Summit sẽ xuất hiện tại đây.", 12f, muted)
        summaryCard.addView(summary)
        summaryCard.addView(summaryDetail, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        val scroll = ScrollView(activity).apply { clipToPadding = false }
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val scrollBody = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        scrollBody.addView(summaryCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        scrollBody.addView(list)
        scroll.addView(scrollBody)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        downloadRows.clear(); downloadEmptyView = null

        val refresh = object : Runnable {
            override fun run() {
                if (!sheet.isShowing) return
                val items = queryDownloads()
                val active = items.count { it.isPending }
                summary.text = "Đang tải $active tệp"
                summaryDetail.text = if (items.isEmpty()) "Chưa có tệp tải xuống nào trong Summit." else "${items.size} mục trong danh sách · cập nhật tự động"
                renderDownloadItems(list, items)
                handler.postDelayed(this, DOWNLOAD_REFRESH_MS)
            }
        }
        sheet.show("Tải xuống", page,
            onDismiss = { handler.removeCallbacks(refresh); downloadRows.clear(); downloadEmptyView = null; clearActiveToneUpdates() },
            onPageToneChanged = { applyActivePageTone(it) }
        )
        handler.post(refresh)
    }

    private fun queryDownloads(): List<DownloadItem> {
        val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val items = mutableListOf<DownloadItem>()
        runCatching {
            dm.query(DownloadManager.Query()).use { c ->
                if (c == null) return@use
                val idC = c.getColumnIndex(DownloadManager.COLUMN_ID)
                val titleC = c.getColumnIndex(DownloadManager.COLUMN_TITLE)
                val mimeC = c.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE)
                val sizeC = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val downC = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val statusC = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val uriC = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                val timeC = c.getColumnIndex(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP)
                while (c.moveToNext()) {
                    val st = c.getInt(statusC)
                    val done = st == DownloadManager.STATUS_SUCCESSFUL
                    val failed = st == DownloadManager.STATUS_FAILED
                    val uriStr = if (uriC >= 0) c.getString(uriC).orEmpty() else ""
                    val fileUri = if (uriStr.isNotBlank()) runCatching { Uri.parse(uriStr) }.getOrNull() ?: Uri.EMPTY else Uri.EMPTY
                    items += DownloadItem(
                        id = if (idC >= 0) c.getLong(idC) else 0L,
                        title = if (titleC >= 0) c.getString(titleC).orEmpty().ifBlank { "Tệp" } else "Tệp",
                        mime = if (mimeC >= 0) c.getString(mimeC).orEmpty() else "",
                        uri = fileUri,
                        size = if (sizeC >= 0) c.getLong(sizeC).coerceAtLeast(0L) else 0L,
                        isPending = !done && !failed,
                        modifiedAt = if (timeC >= 0) c.getLong(timeC) else 0L,
                        read = if (downC >= 0) c.getLong(downC) else 0L,
                        total = if (sizeC >= 0) c.getLong(sizeC) else -1L
                    )
                }
            }
        }.onFailure { onToast("Không đọc được danh sách tải xuống") }
        return items
    }

    private fun renderDownloadItems(container: LinearLayout, items: List<DownloadItem>) {
        if (items.isEmpty()) {
            if (downloadRows.isEmpty() && downloadEmptyView != null) return
            container.removeAllViews(); downloadRows.clear()
            val empty = card()
            empty.addView(text("Chưa có bản tải xuống", 15f, foreground(), true))
            empty.addView(text("Khi bồ tải file từ website, tiến trình và tệp hoàn tất sẽ được hiển thị ở đây.", 13f, muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            container.addView(empty); downloadEmptyView = empty; return
        }
        downloadEmptyView?.let { container.removeView(it); downloadEmptyView = null }
        val visibleItems = items.take(MAX_DOWNLOAD_ROWS)
        val visibleIds = visibleItems.mapTo(mutableSetOf()) { it.id }
        downloadRows.keys.filter { it !in visibleIds }.toList().forEach { id -> downloadRows.remove(id)?.let { container.removeView(it.card) } }
        visibleItems.forEachIndexed { index, item ->
            val row = downloadRows.getOrPut(item.id) { createDownloadRow(item) }
            bindDownloadRow(row, item)
            val currentIndex = container.indexOfChild(row.card)
            if (currentIndex != index) { (row.card.parent as? ViewGroup)?.removeView(row.card); container.addView(row.card, index.coerceAtMost(container.childCount)) }
        }
    }

    private fun createDownloadRow(item: DownloadItem): DownloadRowViews {
        val itemCard = card()
        val title = text(item.title, 14f, foreground(), true).apply { maxLines = 2 }
        itemCard.addView(title)
        val status = text("", 12f, muted).apply { setPadding(0, dp(4), 0, 0) }
        itemCard.addView(status)
        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; isIndeterminate = true
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(if (isNight()) Color.rgb(74, 74, 80) else Color.rgb(225, 225, 230))
        }
        activeToneUpdates += { dark -> progress.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(if (dark) Color.rgb(74, 74, 80) else Color.rgb(225, 225, 230)) }
        itemCard.addView(progress, LinearLayout.LayoutParams(-1, dp(5)).apply { topMargin = dp(10) })
        val size = text("", 11f, muted).apply { setPadding(0, dp(5), 0, 0) }
        itemCard.addView(size)
        val actions = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        itemCard.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        return DownloadRowViews(itemCard, title, status, progress, size, actions)
    }

    private fun bindDownloadRow(row: DownloadRowViews, item: DownloadItem) {
        row.title.text = item.title
        val percent = if (item.total > 0L) ((item.read * 100L) / item.total).toInt().coerceIn(0, 100) else null
        row.status.text = when {
            item.isPending && percent != null -> "Đang tải xuống · $percent%"
            item.isPending -> "Đang tải xuống..."
            else -> "Đã tải xong · ${formatDate(item.modifiedAt)}"
        }
        row.progress.visibility = if (item.isPending) View.VISIBLE else View.GONE
        row.progress.isIndeterminate = item.isPending && percent == null
        if (percent != null) row.progress.progress = percent
        row.size.visibility = View.VISIBLE
        row.size.text = if (item.isPending && item.total > 0L) "${formatBytes(item.read)} / ${formatBytes(item.total)}" else formatBytes(item.size)
        val actionType = if (item.isPending) "pending" else "complete"
        if (row.actionType != actionType) {
            row.actions.removeAllViews()
            if (!item.isPending) {
                row.actions.addView(actionButton("Mở", accent) { openFile(item.uri, item.mime) }, LinearLayout.LayoutParams(-2, dp(38)).apply { marginEnd = dp(6) })
                row.actions.addView(actionButton("Chia sẻ", muted) { shareFile(item.uri, item.mime) }, LinearLayout.LayoutParams(-2, dp(38)).apply { marginEnd = dp(6) })
            }
            row.actions.addView(actionButton(if (item.isPending) "Hủy tải" else "Xóa", if (item.isPending) muted else Theme.DANGER_RED) { confirmRemove(item) }, LinearLayout.LayoutParams(-2, dp(38)))
            row.actionType = actionType
        }
    }

    private fun openFile(uri: Uri, mime: String) { runCatching { val intent = Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, mime.ifBlank { "*/*" }); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }; activity.startActivity(Intent.createChooser(intent, "Mở tệp")) }.onFailure { onToast("Không tìm thấy ứng dụng mở loại tệp này") } }
    private fun shareFile(uri: Uri, mime: String) { runCatching { val intent = Intent(Intent.ACTION_SEND).apply { type = mime.ifBlank { "*/*" }; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }; activity.startActivity(Intent.createChooser(intent, "Chia sẻ tệp")) }.onFailure { onToast("Không thể chia sẻ tệp này") } }
    private fun confirmRemove(item: DownloadItem) {
        sheet.confirm(title = if (item.isPending) "Hủy tải xuống?" else "Xóa tệp tải xuống?", message = if (item.isPending) "Summit sẽ hủy tiến trình tải ${item.title}." else "Tệp ${item.title} sẽ bị xóa khỏi danh sách tải xuống và thiết bị.", positive = if (item.isPending) "Hủy tải" else "Xóa", negative = "Giữ lại") {
            if (item.isPending) {
                cancelDownload(item.id)
            } else {
                val deleted = runCatching { activity.contentResolver.delete(item.uri, null, null) }.getOrDefault(0)
                onToast(if (deleted > 0) "Đã xóa tệp tải xuống" else "Không xóa được tệp")
            }
        }
    }

    fun showPrivacy() {
        clearActiveToneUpdates()
        val page = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(22)) }
        val scroll = ScrollView(activity).apply { clipToPadding = false }
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body); page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        renderPrivacy(body)
        sheet.show("Privacy Dashboard", page, onDismiss = { clearActiveToneUpdates() }, onPageToneChanged = { applyActivePageTone(it) })
    }

    private fun renderPrivacy(body: LinearLayout) {
        activeToneUpdates.clear(); body.removeAllViews()
        val statsCard = card()
        statsCard.addView(text("Bảo vệ theo dõi của Gecko", 14f, foreground(), true))
        statsCard.addView(text("GeckoView sử dụng Enhanced Tracking Protection (ETP) + Summit AdBlock để chặn trình theo dõi, quảng cáo và mã độc.", 13f, muted).apply { setPadding(0, dp(5), 0, 0) })
        val statRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0) }
        val (blocked, hosts) = adStats()
        statRow.addView(text("$blocked request bị chặn", 13f, foreground(), true), LinearLayout.LayoutParams(0, -2, 1f))
        statRow.addView(text("$hosts host", 12f, muted), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        statRow.addView(actionButton("Đặt lại", muted) { onResetAdStats(); renderPrivacy(body) }, LinearLayout.LayoutParams(-2, dp(32)))
        statsCard.addView(statRow) (ETP) để chặn trình theo dõi và mã độc.", 13f, muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
        body.addView(statsCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        val filterCard = card()
        val filterRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val filterTexts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        filterTexts.addView(text("Chặn quảng cáo và theo dõi", 14f, foreground(), true))
        filterTexts.addView(text("Áp dụng bộ lọc ETP của Gecko cho mọi tab.", 11f, muted).apply { setPadding(0, dp(3), 0, 0) })
        filterRow.addView(filterTexts, LinearLayout.LayoutParams(0, -2, 1f))
        val enabled = Switch(activity).apply { isChecked = adBlockEnabled(); setOnCheckedChangeListener { _: CompoundButton, checked: Boolean -> onToggleAdBlock(); onToast(if (checked) "Đã bật chặn quảng cáo" else "Đã tắt chặn quảng cáo") } }
        filterRow.addView(enabled); filterCard.addView(filterRow)
        body.addView(filterCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        val ytCard = card()
        val ytRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val ytTexts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        ytTexts.addView(text("YouTube AdBlock", 14f, foreground(), true))
        ytTexts.addView(text("Lọc quảng cáo YouTube qua Summit Probe (cần bật chặn quảng cáo).", 11f, muted).apply { setPadding(0, dp(3), 0, 0) })
        ytRow.addView(ytTexts, LinearLayout.LayoutParams(0, -2, 1f))
        ytRow.addView(Switch(activity).apply {
            isChecked = youtubeAdBlockEnabled()
            setOnCheckedChangeListener { _: CompoundButton, _: Boolean -> onToggleYoutubeAdBlock() }
        })
        ytCard.addView(ytRow)
        body.addView(ytCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })

        addSectionTitle(body, "Quyền của website hiện tại")
        val origin = originFrom(currentUrl())
        if (origin == null) {
            val noSite = card(); noSite.addView(text("Chưa có website hợp lệ để quản lý quyền.", 13f, muted))
            body.addView(noSite, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        } else {
            val currentCard = card()
            currentCard.addView(text(displayOrigin(origin), 13f, foreground(), true))
            currentCard.addView(text("Cài đặt bên dưới áp dụng cho origin hiện tại. Quyền hệ thống Android vẫn được kiểm tra riêng.", 11f, muted).apply { setPadding(0, dp(4), 0, dp(8)) })
            if (isPrivateMode()) {
                currentCard.addView(text("Phiên riêng tư: camera, micro và vị trí luôn hỏi lại; lựa chọn không được lưu.", 12f, muted).apply { setPadding(0, dp(4), 0, dp(4)) })
            } else {
                addPermissionControl(currentCard, "Camera và microphone", store.getMediaPermission(origin)) { store.setMediaPermission(origin, it) }
                addPermissionControl(currentCard, "Vị trí", store.getGeoPermission(origin)) { store.setGeoPermission(origin, it) }
            }
            body.addView(currentCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        }

        addSectionTitle(body, "Quyền đã lưu cho website khác")
        val otherPermissions = if (isPrivateMode()) emptyList() else store.sitePermissionEntries().filter { it.origin != origin }
        if (otherPermissions.isEmpty()) {
            val empty = card(); empty.addView(text("Chưa có quyền nào được lưu cho website khác.", 12f, muted))
            body.addView(empty, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        } else {
            otherPermissions.take(MAX_PERMISSION_ORIGINS).forEach { entry ->
                val savedCard = card()
                savedCard.addView(text(displayOrigin(entry.origin), 13f, foreground(), true))
                val summary = buildList { if (entry.media != PermissionState.ASK) add("Camera/mic: ${permissionLabel(entry.media)}"); if (entry.location != PermissionState.ASK) add("Vị trí: ${permissionLabel(entry.location)}") }.joinToString(" · ").ifBlank { "Không có quyền đã lưu" }
                savedCard.addView(text(summary, 11f, muted).apply { setPadding(0, dp(4), 0, 0) })
                savedCard.addView(actionButton("Đặt lại quyền", muted) { store.clearSitePermissions(entry.origin); renderPrivacy(body) }, LinearLayout.LayoutParams(-2, dp(36)).apply { topMargin = dp(8) })
                body.addView(savedCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            }
            if (otherPermissions.size > MAX_PERMISSION_ORIGINS) body.addView(text("Còn ${otherPermissions.size - MAX_PERMISSION_ORIGINS} website khác.", 11f, muted))
            body.addView(actionButton("Đặt lại tất cả quyền đã lưu", Theme.DANGER_RED) {
                sheet.confirm(title = "Đặt lại mọi quyền website?", message = "Tất cả lựa chọn cho phép/từ chối của website sẽ trở về trạng thái hỏi lại.", positive = "Đặt lại") { store.clearSitePermissions(null); onClearPermissions(); renderPrivacy(body) }
            }, LinearLayout.LayoutParams(-2, dp(38)).apply { topMargin = dp(8); bottomMargin = dp(12) })
        }
        addSectionTitle(body, "Dữ liệu website")
        val dataCard = card()
        dataCard.addView(text("Cookie và dữ liệu trang", 13f, foreground(), true))
        dataCard.addView(text("Xóa dữ liệu duyệt web trong Gecko. Thao tác không thể hoàn tác.", 11f, muted).apply { setPadding(0, dp(4), 0, dp(8)) })
        val dataRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        dataRow.addView(actionButton("Xóa cookie", Theme.DANGER_RED) {
            sheet.confirm(title = "Xóa tất cả cookie?", message = "Thao tác này đăng xuất các website trong Summit.", positive = "Xóa cookie") { onClearCookies(); onToast("Đã xóa cookie") }
        }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(8) })
        dataRow.addView(actionButton("Xóa cache", muted) {
            sheet.confirm(title = "Xóa cache?", message = "Cache tài nguyên bị xóa; đăng nhập giữ nguyên.", positive = "Xóa cache") { onClearCache(); onToast("Đã xóa cache") }
        }, LinearLayout.LayoutParams(0, dp(38), 1f))
        dataCard.addView(dataRow)
        dataCard.addView(actionButton("Xóa toàn bộ dữ liệu trang", Theme.DANGER_RED) {
            sheet.confirm(title = "Xóa toàn bộ?", message = "Cookie, cache và storage của mọi website sẽ bị xóa.", positive = "Xóa tất cả") { onClearSiteData(); onToast("Đã xóa dữ liệu trang") }
        }, LinearLayout.LayoutParams(-2, dp(38)).apply { topMargin = dp(8) })
        body.addView(dataCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        applyActivePageTone(GlassSystem.currentPageBackground())
    }

    private fun addPermissionControl(parent: LinearLayout, label: String, initialState: PermissionState, onChange: (PermissionState) -> Unit) {
        val group = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(text(label, 12f, foreground(), true), LinearLayout.LayoutParams(0, -2, 1f))
        val stateText = text(permissionLabel(initialState), 11f, muted)
        row.addView(stateText); group.addView(row)
        val choices = listOf("Hỏi lại" to PermissionState.ASK, "Cho phép" to PermissionState.ALLOW, "Chặn" to PermissionState.DENY)
        val control = GlassSystem.SegmentedControl(context = activity, density = density, isNight = isNight(), labels = choices.map { it.first }, initialIndex = choices.indexOfFirst { it.second == initialState }, selectedColor = accent, normalColor = muted, trackFill = GlassSystem.createSegmentedTrack(24, density, isNight())) { index -> val state = choices[index].second; onChange(state); stateText.text = permissionLabel(state); onToast("Đã cập nhật quyền: $label") }
        activeToneUpdates += { dark -> control.applyPageTone(dark, selectedTextColor = accent, normalTextColor = if (dark) Color.rgb(202, 204, 212) else Color.rgb(82, 82, 88)) }
        group.addView(control.view, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(7) })
        group.clipChildren = false; group.clipToPadding = false; parent.clipChildren = false; parent.clipToPadding = false
        parent.addView(group, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }

    private fun originFrom(rawUrl: String): String? = runCatching { val uri = Uri.parse(rawUrl); val scheme = uri.scheme?.takeIf { it == "http" || it == "https" } ?: return@runCatching null; val host = uri.host?.takeIf { it.isNotBlank() } ?: return@runCatching null; "$scheme://$host${if (uri.port != -1) ":${uri.port}" else ""}" }.getOrNull()
    private fun displayOrigin(origin: String): String = runCatching { Uri.parse(origin).host?.removePrefix("www.") }.getOrNull() ?: origin
    private fun permissionLabel(state: PermissionState): String = when (state) { PermissionState.ASK -> "Hỏi mỗi lần"; PermissionState.ALLOW -> "Đã cho phép"; PermissionState.DENY -> "Đã chặn" }
    private fun addSectionTitle(parent: LinearLayout, title: String) { parent.addView(text(title, 14f, foreground(), true).apply { setPadding(dp(3), dp(3), dp(3), dp(8)) }) }

    private fun card(): LinearLayout {
        val card = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; setPadding(dp(14), dp(13), dp(14), dp(13)) }
        val surface = roundedDrawable(dp(18).toFloat(), cardColor)
        card.background = surface
        activeToneUpdates += { dark -> surface.setColor(if (dark) Color.rgb(43, 45, 52) else Color.rgb(247, 247, 249)); card.invalidate() }
        return card
    }
    private fun text(value: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(activity).apply { text = value; textSize = size; setTextColor(color); if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private fun actionButton(label: String, color: Int, onClick: () -> Unit): TextView {
        val button = TextView(activity).apply { text = label; textSize = 12f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); gravity = Gravity.CENTER; setPadding(dp(12), 0, dp(12), 0) }
        val usesMutedColor = color == muted
        button.setTextColor(if (usesMutedColor) foreground() else color)
        val buttonSurface = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(if (isNight()) Color.rgb(58, 58, 64) else Color.rgb(238, 238, 242)); setStroke(dp(1), if (usesMutedColor) { if (isNight()) Color.rgb(82, 82, 88) else Color.rgb(218, 218, 224) } else color) }
        button.background = buttonSurface
        activeToneUpdates += { dark -> buttonSurface.setColor(if (dark) Color.rgb(58, 58, 64) else Color.rgb(238, 238, 242)); buttonSurface.setStroke(dp(1), if (usesMutedColor) { if (dark) Color.rgb(82, 82, 88) else Color.rgb(218, 218, 224) } else color); button.invalidate() }
        button.setOnClickListener { onClick() }; AnimationSystem.press(button, Constants.PRESS_SCALE_PILL); return button
    }
    private fun roundedDrawable(radiusPx: Float, color: Int): GradientDrawable = GradientDrawable().apply { cornerRadius = radiusPx; setColor(color) }
    private fun formatBytes(value: Long): String { if (value < 0L) return "Không rõ dung lượng"; if (value < 1024L) return "$value B"; val units = arrayOf("KB", "MB", "GB", "TB"); var amount = value.toDouble(); var index = -1; do { amount /= 1024.0; index++ } while (amount >= 1024.0 && index < units.lastIndex); return "${DecimalFormat("#0.0").format(amount)} ${units[index]}" }
    private fun formatDate(timestamp: Long): String = if (timestamp <= 0L) "" else DateFormat.format("dd/MM · HH:mm", Date(timestamp)).toString()
    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()
    val isShowing: Boolean get() = sheet.isShowing
    fun dispose() { sheet.dismiss(immediate = true) }
    fun close() { sheet.dismiss() }
    private companion object { const val DOWNLOAD_REFRESH_MS = 1100L; const val MAX_DOWNLOAD_ROWS = 40; const val MAX_PERMISSION_ORIGINS = 12 }
}
