package com.example.webedge

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PromptDelegate

/**
 * alert / confirm / prompt của trang web trên hộp thoại kính mờ Summit.
 * Chỉ tab đang xem được hiện hộp thoại; tab nền bị bỏ qua (dismiss). Quá 5 hộp thoại đang chờ
 * cũng bị bỏ qua để trang không thể chặn trình duyệt bằng vòng lặp alert.
 * Cũng xử lý: chọn tệp, danh sách lựa chọn (<select>), xác thực HTTP, chọn ngày giờ và chọn màu.
 * Các loại prompt khác (popup bị chặn, chia sẻ...) chưa xử lý: giữ nguyên hành vi cũ (bỏ qua).
 */
internal class GeckoPromptController(
    private val dialog: () -> GlassDialogController,
    private val context: () -> android.content.Context,
    private val isActiveSession: (GeckoSession) -> Boolean,
    private val urlOf: (GeckoSession) -> String?,
    private val launchFilePicker: (Array<String>?, Boolean, (Array<Uri>?) -> Unit) -> Boolean
) : PromptDelegate {

    companion object {
        private const val MAX_PENDING = 5
        private const val MAX_TEXT = 800
    }

    private var pendingCount = 0

    private var filePromptPending = false

    override fun onFilePrompt(
        session: GeckoSession,
        prompt: PromptDelegate.FilePrompt
    ): GeckoResult<PromptDelegate.PromptResponse>? {
        if (!canShow(session) || filePromptPending ||
            prompt.type == PromptDelegate.FilePrompt.Type.FOLDER
        ) {
            return GeckoResult.fromValue(prompt.dismiss())
        }

        val result = GeckoResult<PromptDelegate.PromptResponse>()
        filePromptPending = true
        val multiple = prompt.type == PromptDelegate.FilePrompt.Type.MULTIPLE
        val launched = runCatching {
            launchFilePicker(prompt.mimeTypes, multiple) { uris ->
                filePromptPending = false
                val response = if (uris.isNullOrEmpty()) {
                    prompt.dismiss()
                } else if (multiple) {
                    prompt.confirm(context(), uris)
                } else {
                    prompt.confirm(context(), uris.first())
                }
                result.complete(response)
            }
        }.getOrDefault(false)

        if (!launched) {
            filePromptPending = false
            return GeckoResult.fromValue(prompt.dismiss())
        }
        return result
    }


    private fun canShow(session: GeckoSession) = isActiveSession(session) && pendingCount < MAX_PENDING

    private fun titleFor(session: GeckoSession): String {
        val host = urlOf(session)?.let { runCatching { Uri.parse(it).host }.getOrNull() }
        return if (host.isNullOrBlank()) "Trang web cho biết" else "$host cho biết"
    }

    override fun onAlertPrompt(
        session: GeckoSession,
        prompt: PromptDelegate.AlertPrompt
    ): GeckoResult<PromptDelegate.PromptResponse>? {
        if (!canShow(session)) return GeckoResult.fromValue(prompt.dismiss())
        val result = GeckoResult<PromptDelegate.PromptResponse>()
        pendingCount++
        dialog().show(
            title = titleFor(session),
            message = prompt.message.orEmpty().take(MAX_TEXT),
            positive = "OK"
        ) { _, _ ->
            pendingCount--
            result.complete(prompt.dismiss())
        }
        return result
    }

    override fun onButtonPrompt(
        session: GeckoSession,
        prompt: PromptDelegate.ButtonPrompt
    ): GeckoResult<PromptDelegate.PromptResponse>? {
        if (!canShow(session)) return GeckoResult.fromValue(prompt.dismiss())
        val result = GeckoResult<PromptDelegate.PromptResponse>()
        pendingCount++
        dialog().show(
            title = titleFor(session),
            message = prompt.message.orEmpty().take(MAX_TEXT),
            positive = "OK", negative = "Hủy"
        ) { confirmed, _ ->
            pendingCount--
            result.complete(
                prompt.confirm(
                    if (confirmed) PromptDelegate.ButtonPrompt.Type.POSITIVE
                    else PromptDelegate.ButtonPrompt.Type.NEGATIVE
                )
            )
        }
        return result
    }

    override fun onTextPrompt(
        session: GeckoSession,
        prompt: PromptDelegate.TextPrompt
    ): GeckoResult<PromptDelegate.PromptResponse>? {
        if (!canShow(session)) return GeckoResult.fromValue(prompt.dismiss())
        val result = GeckoResult<PromptDelegate.PromptResponse>()
        pendingCount++
        dialog().show(
            title = titleFor(session),
            message = prompt.message.orEmpty().take(MAX_TEXT),
            positive = "OK", negative = "Hủy",
            defaultInput = prompt.defaultValue.orEmpty().take(MAX_TEXT)
        ) { confirmed, input ->
            pendingCount--
            result.complete(if (confirmed) prompt.confirm(input.orEmpty()) else prompt.dismiss())
        }
        return result
    }

    // ---- Xác thực HTTP -----------------------------------------------------------------------

    override fun onAuthPrompt(
        session: GeckoSession,
        prompt: PromptDelegate.AuthPrompt
    ): GeckoResult<PromptDelegate.PromptResponse>? {
        if (!canShow(session)) return GeckoResult.fromValue(prompt.dismiss())
        val options = prompt.authOptions
        val onlyPassword = options.flags and PromptDelegate.AuthPrompt.AuthOptions.Flags.ONLY_PASSWORD != 0
        val failedBefore = options.flags and PromptDelegate.AuthPrompt.AuthOptions.Flags.PREVIOUS_FAILED != 0
        val host = runCatching { Uri.parse(options.uri).host }.getOrNull().orEmpty()
        val message = buildString {
            val base = prompt.message.orEmpty().take(MAX_TEXT)
            if (base.isNotBlank()) append(base) else append("Trang yêu cầu đăng nhập")
            if (failedBefore) append("\n\nThông tin đăng nhập trước đó không đúng.")
        }
        val result = GeckoResult<PromptDelegate.PromptResponse>()
        pendingCount++
        dialog().show(
            title = if (host.isBlank()) "Đăng nhập" else "Đăng nhập $host",
            message = message,
            positive = "Đăng nhập", negative = "Hủy",
            defaultInput = if (onlyPassword) null else options.username.orEmpty().take(MAX_TEXT),
            secondInput = ""
        ) { confirmed, user ->
            pendingCount--
            val password = dialog().lastSecondInput.orEmpty()
            result.complete(
                when {
                    !confirmed -> prompt.dismiss()
                    onlyPassword -> prompt.confirm(password)
                    else -> prompt.confirm(user.orEmpty(), password)
                }
            )
        }
        return result
    }

    // ---- Danh sách lựa chọn (<select>) --------------------------------------------------------

    override fun onChoicePrompt(
        session: GeckoSession,
        prompt: PromptDelegate.ChoicePrompt
    ): GeckoResult<PromptDelegate.PromptResponse>? {
        if (!canShow(session)) return GeckoResult.fromValue(prompt.dismiss())

        // Làm phẳng nhóm (<optgroup>): tiêu đề nhóm là dòng bị khóa (entry = null).
        val entries = ArrayList<PromptDelegate.ChoicePrompt.Choice?>()
        val labels = ArrayList<String>()
        val disabled = HashSet<Int>()
        val selected = HashSet<Int>()
        fun walk(list: Array<PromptDelegate.ChoicePrompt.Choice>?, depth: Int) {
            if (list == null) return
            for (choice in list) {
                if (entries.size >= 500) return
                if (choice.separator) continue
                val children = choice.items
                val indent = if (depth > 0) "    " else ""
                if (children != null && children.isNotEmpty()) {
                    disabled.add(entries.size)
                    entries.add(null)
                    labels.add(indent + choice.label.orEmpty().take(MAX_TEXT))
                    walk(children, depth + 1)
                } else {
                    val index = entries.size
                    if (choice.disabled) disabled.add(index)
                    if (choice.selected) selected.add(index)
                    entries.add(choice)
                    labels.add(indent + choice.label.orEmpty().take(MAX_TEXT))
                }
            }
        }
        walk(prompt.choices, 0)
        if (labels.isEmpty()) return GeckoResult.fromValue(prompt.dismiss())

        val multiple = prompt.type == PromptDelegate.ChoicePrompt.Type.MULTIPLE
        val result = GeckoResult<PromptDelegate.PromptResponse>()
        pendingCount++
        dialog().show(
            title = prompt.message?.takeIf { it.isNotBlank() }?.take(MAX_TEXT) ?: titleFor(session),
            message = "",
            positive = "Xong", negative = "Hủy",
            choices = labels, choiceMulti = multiple,
            choiceSelected = selected, choiceDisabled = disabled
        ) { confirmed, _ ->
            pendingCount--
            val picked = dialog().lastChoiceIndices.mapNotNull { entries.getOrNull(it) }
            result.complete(
                when {
                    !confirmed || picked.isEmpty() && !multiple -> prompt.dismiss()
                    multiple -> prompt.confirm(picked.toTypedArray())
                    else -> prompt.confirm(picked.first())
                }
            )
        }
        return result
    }

    // ---- Chọn màu -------------------------------------------------------------------------------

    override fun onColorPrompt(
        session: GeckoSession,
        prompt: PromptDelegate.ColorPrompt
    ): GeckoResult<PromptDelegate.PromptResponse>? {
        if (!canShow(session)) return GeckoResult.fromValue(prompt.dismiss())
        val result = GeckoResult<PromptDelegate.PromptResponse>()
        pendingCount++
        dialog().show(
            title = titleFor(session),
            message = "Nhập mã màu, ví dụ #1A73E8",
            positive = "OK", negative = "Hủy",
            defaultInput = prompt.defaultValue.orEmpty().ifBlank { "#000000" }.take(16)
        ) { confirmed, input ->
            pendingCount--
            val raw = input.orEmpty().trim()
            val hex = if (raw.startsWith("#")) raw else "#$raw"
            result.complete(
                if (confirmed && Regex("^#[0-9a-fA-F]{6}$").matches(hex)) prompt.confirm(hex.lowercase(Locale.ROOT))
                else prompt.dismiss()
            )
        }
        return result
    }

    // ---- Chọn ngày giờ ---------------------------------------------------------------------------

    override fun onDateTimePrompt(
        session: GeckoSession,
        prompt: PromptDelegate.DateTimePrompt
    ): GeckoResult<PromptDelegate.PromptResponse>? {
        if (!canShow(session)) return GeckoResult.fromValue(prompt.dismiss())
        val type = prompt.type
        val pattern = when (type) {
            PromptDelegate.DateTimePrompt.Type.DATE -> "yyyy-MM-dd"
            PromptDelegate.DateTimePrompt.Type.MONTH -> "yyyy-MM"
            PromptDelegate.DateTimePrompt.Type.TIME -> "HH:mm"
            PromptDelegate.DateTimePrompt.Type.DATETIME_LOCAL -> "yyyy-MM-dd'T'HH:mm"
            else -> return GeckoResult.fromValue(prompt.dismiss()) // tuần (WEEK): chưa hỗ trợ
        }
        val format = SimpleDateFormat(pattern, Locale.ROOT).apply { isLenient = false }
        fun parse(value: String?): Calendar? {
            if (value.isNullOrBlank()) return null
            val date = runCatching { format.parse(value) }.getOrNull() ?: return null
            return Calendar.getInstance().apply { time = date }
        }

        val initial = parse(prompt.defaultValue) ?: Calendar.getInstance()
        val result = GeckoResult<PromptDelegate.PromptResponse>()
        var done = false
        fun finishWith(value: String?) {
            if (done) return
            done = true
            result.complete(if (value == null) prompt.dismiss() else prompt.confirm(value))
        }

        val ctx = context()
        fun pickTime(base: Calendar) {
            TimePickerDialog(ctx, { _, hour, minute ->
                base.set(Calendar.HOUR_OF_DAY, hour)
                base.set(Calendar.MINUTE, minute)
                finishWith(format.format(base.time))
            }, initial.get(Calendar.HOUR_OF_DAY), initial.get(Calendar.MINUTE), true).apply {
                setOnDismissListener { finishWith(null) }
            }.show()
        }

        if (type == PromptDelegate.DateTimePrompt.Type.TIME) {
            pickTime(Calendar.getInstance())
        } else {
            DatePickerDialog(
                ctx, { _, year, month, day ->
                    val picked = Calendar.getInstance().apply { clear(); set(year, month, day) }
                    if (type == PromptDelegate.DateTimePrompt.Type.DATETIME_LOCAL) {
                        pickTime(picked)
                    } else {
                        finishWith(format.format(picked.time))
                    }
                },
                initial.get(Calendar.YEAR), initial.get(Calendar.MONTH), initial.get(Calendar.DAY_OF_MONTH)
            ).apply {
                if (type == PromptDelegate.DateTimePrompt.Type.DATE) {
                    parse(prompt.minValue)?.let { datePicker.minDate = it.timeInMillis }
                    parse(prompt.maxValue)?.let { datePicker.maxDate = it.timeInMillis }
                }
                // Với DATETIME_LOCAL, hộp ngày đóng lại trước khi hộp giờ mở: chỉ hủy khi người dùng thật sự hủy.
                setOnCancelListener { finishWith(null) }
                if (type != PromptDelegate.DateTimePrompt.Type.DATETIME_LOCAL) setOnDismissListener { finishWith(null) }
            }.show()
        }
        return result
    }
}
