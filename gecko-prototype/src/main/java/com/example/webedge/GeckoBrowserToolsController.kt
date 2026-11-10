package com.example.webedge

import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import eightbitlab.com.blurview.BlurTarget
import java.text.DecimalFormat

/** Gecko-native Downloads and Privacy Dashboard. Uses MediaStore + Summit Store, never WebView APIs. */
internal class GeckoBrowserToolsController(
    private val activity: Activity,
    private val root: FrameLayout,
    private val blurTarget: BlurTarget,
    private val store: Store,
    private val dialog: () -> GlassDialogController,
    private val density: Float,
    private val isNight: () -> Boolean,
    private val foreground: () -> Int,
    private val adBlockEnabled: () -> Boolean,
    private val onToggleAdBlock: () -> Unit,
    private val onClearPermissions: () -> Unit,
    private val onToast: (String) -> Unit
) {
    private var layer: FrameLayout? = null
    private var card: FrameLayout? = null
    private var body: LinearLayout? = null
    private var titleView: TextView? = null
    val isShowing: Boolean get() = layer != null

    private fun px(v: Int) = (v * density + 0.5f).toInt()
    private fun muted() = if (isNight()) Color.rgb(190, 192, 200) else Color.rgb(92, 94, 102)

    private fun open(title: String) {
        close(immediate = true)
        val night = isNight()
        val hostLayer = FrameLayout(activity).apply { setOnClickListener { close() } }
        val panel = FrameLayout(activity).apply {
            background = GlassSystem.create(GlassSystem.Preset.MENU_CARD, density, night)
            foreground = GlassSystem.createBorder(GlassSystem.Preset.MENU_CARD.radiusDp, density, night)
            clipToOutline = true
            isClickable = true
            setOnClickListener { /* consume */ }
            alpha = 0f
            translationY = px(32).toFloat()
        }
        panel.addView(GlassSystem.createBlurView(activity, blurTarget, GlassSystem.Preset.MENU_CARD, density, night, rim = false), FrameLayout.LayoutParams(-1, -1))
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(px(16), px(10), px(16), px(16)) }
        val header = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val back = TextView(activity).apply {
            text = "‹"; textSize = 30f; gravity = Gravity.CENTER; setTextColor(foreground())
            background = activity.roundedPressBackground(night)
            setOnClickListener { close() }
        }
        header.addView(back, LinearLayout.LayoutParams(px(42), px(42)))
        val heading = TextView(activity).apply {
            text = title; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(foreground())
        }
        header.addView(heading, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = px(10) })
        val closeButton = TextView(activity).apply {
            text = "×"; textSize = 25f; gravity = Gravity.CENTER; setTextColor(foreground())
            background = activity.roundedPressBackground(night); setOnClickListener { close() }
        }
        header.addView(closeButton, LinearLayout.LayoutParams(px(42), px(42)))
        column.addView(header)
        val scroll = ScrollView(activity).apply { clipToPadding = false; overScrollMode = View.OVER_SCROLL_NEVER }
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(0, px(8), 0, px(10)) }
        scroll.addView(content)
        column.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        panel.addView(column, FrameLayout.LayoutParams(-1, -1))
        hostLayer.addView(panel, FrameLayout.LayoutParams(-1, (activity.resources.displayMetrics.heightPixels * 0.82f).toInt(), Gravity.BOTTOM).apply {
            leftMargin = px(8); rightMargin = px(8); bottomMargin = px(8)
        })
        root.addView(hostLayer, FrameLayout.LayoutParams(-1, -1))
        layer = hostLayer; card = panel; body = content; titleView = heading
        panel.post { if (card === panel) panel.animate().alpha(1f).translationY(0f).setDuration(230L).start() }
    }

    fun showDownloads() {
        open("Tải xuống")
        val content = body ?: return
        addText(content, "Các tệp Summit tải xuống", 14f, foreground(), true)
        addText(content, "Lưu tại Download/Summit · dữ liệu đọc từ MediaStore", 11f, muted())
        addAction(content, "Làm mới danh sách") { showDownloads() }
        val items = queryDownloads()
        if (items.isEmpty()) addText(content, "Chưa có tệp tải xuống nào.", 13f, muted())
        items.forEach { item ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = activity.roundedPressBackground(isNight())
                setPadding(px(12), px(10), px(12), px(10))
            }
            row.addView(TextView(activity).apply {
                text = item.name; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(foreground()); maxLines = 2
            })
            row.addView(TextView(activity).apply {
                text = "${formatBytes(item.size)} · ${item.mime.ifBlank { "Tệp" }}"; textSize = 11f; setTextColor(muted())
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(3) })
            val actions = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
            addSmallAction(actions, "Mở") { openFile(item.uri) }
            addSmallAction(actions, "Chia sẻ") { shareFile(item.uri, item.mime) }
            addSmallAction(actions, "Xóa") { deleteFile(item.uri) }
            row.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(5) })
            content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8) })
        }
    }

    fun showPrivacy() {
        open("Privacy Dashboard")
        val content = body ?: return
        addText(content, "Bảo vệ theo dõi của Gecko", 14f, foreground(), true)
        addText(content, if (adBlockEnabled()) "Chặn quảng cáo và một số trình theo dõi đang bật." else "Chặn quảng cáo và một số trình theo dõi đang tắt.", 12f, muted())
        addAction(content, if (adBlockEnabled()) "Tắt chặn quảng cáo / theo dõi" else "Bật chặn quảng cáo / theo dõi") {
            onToggleAdBlock(); showPrivacy()
        }
        addText(content, "Quyền trang web đã lưu", 14f, foreground(), true)
        val entries = store.sitePermissionEntries()
        if (entries.isEmpty()) addText(content, "Chưa có trang web nào được lưu quyền.", 12f, muted())
        entries.forEach { entry ->
            val media = permissionLabel(entry.media)
            val geo = permissionLabel(entry.location)
            addText(content, "${entry.origin}\nCamera/Micro: $media · Vị trí: $geo", 12f, foreground())
            addDivider(content)
        }
        addAction(content, "Đặt lại tất cả quyền trang web", danger = true) {
            store.clearSitePermissions(null)
            onClearPermissions()
            onToast("Đã đặt lại quyền trang web")
            showPrivacy()
        }
    }

    private data class DownloadItem(val uri: Uri, val name: String, val mime: String, val size: Long)

    private fun queryDownloads(): List<DownloadItem> {
        val result = mutableListOf<DownloadItem>()
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.RELATIVE_PATH
        )
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        runCatching {
            activity.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                arrayOf("Download/Summit/"),
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                while (cursor.moveToNext()) {
                    val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(idCol))
                    result += DownloadItem(uri, cursor.getString(nameCol).orEmpty(), cursor.getString(mimeCol).orEmpty(), cursor.getLong(sizeCol))
                }
            }
        }.onFailure { onToast("Không đọc được danh sách tải xuống") }
        return result
    }

    private fun openFile(uri: Uri) {
        runCatching {
            val mime = activity.contentResolver.getType(uri) ?: "application/octet-stream"
            activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.onFailure { onToast("Không tìm thấy ứng dụng mở tệp") }
    }

    private fun shareFile(uri: Uri, mime: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mime.ifBlank { "application/octet-stream" }
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(intent, "Chia sẻ tệp"))
        }.onFailure { onToast("Không thể chia sẻ tệp") }
    }

    private fun deleteFile(uri: Uri) {
        dialog().show(
            title = "Xóa tệp?", message = "Tệp này sẽ bị xóa khỏi Download/Summit.", positive = "Xóa", negative = "Hủy"
        ) { confirmed, _ ->
            if (confirmed) {
                val deleted = runCatching { activity.contentResolver.delete(uri, null, null) }.getOrDefault(0)
                onToast(if (deleted > 0) "Đã xóa tệp" else "Không xóa được tệp")
                showDownloads()
            }
        }
    }

    private fun permissionLabel(value: PermissionState) = when (value) {
        PermissionState.ALLOW -> "Cho phép"
        PermissionState.DENY -> "Đã chặn"
        PermissionState.ASK -> "Hỏi"
    }

    private fun addText(parent: LinearLayout, value: String, size: Float, color: Int, bold: Boolean = false) {
        parent.addView(TextView(activity).apply {
            text = value; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(px(5), px(9), px(5), px(9))
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun addAction(parent: LinearLayout, label: String, danger: Boolean = false, action: () -> Unit) {
        parent.addView(TextView(activity).apply {
            text = label; textSize = 13f; setTextColor(if (danger) Color.rgb(220, 65, 75) else foreground())
            gravity = Gravity.CENTER_VERTICAL; background = activity.roundedPressBackground(isNight())
            setPadding(px(12), px(12), px(12), px(12)); setOnClickListener { action() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(5) })
    }

    private fun addSmallAction(parent: LinearLayout, label: String, action: () -> Unit) {
        parent.addView(TextView(activity).apply {
            text = label; textSize = 11f; setTextColor(foreground()); gravity = Gravity.CENTER
            background = activity.roundedPressBackground(isNight()); setPadding(px(12), px(8), px(12), px(8)); setOnClickListener { action() }
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(5) })
    }

    private fun addDivider(parent: LinearLayout) {
        parent.addView(View(activity).apply { setBackgroundColor(GlassSystem.pageDividerColor()) }, LinearLayout.LayoutParams(-1, px(1)))
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "Dung lượng không xác định"
        val units = arrayOf("B", "KB", "MB", "GB")
        var size = bytes.toDouble(); var index = 0
        while (size >= 1024 && index < units.lastIndex) { size /= 1024; index++ }
        return "${DecimalFormat("#,##0.#").format(size)} ${units[index]}"
    }

    fun dispose() {
        card?.animate()?.cancel()
        layer?.let { root.removeView(it) }
        layer = null; card = null; body = null; titleView = null
    }

    fun close(immediate: Boolean = false) {
        val oldLayer = layer ?: return
        val oldCard = card
        layer = null; card = null; body = null; titleView = null
        if (immediate || oldCard == null) { root.removeView(oldLayer); return }
        oldCard.animate().alpha(0f).translationY(px(24).toFloat()).setDuration(170L).withEndAction {
            if (oldLayer.parent === root) root.removeView(oldLayer)
        }.start()
    }
}
