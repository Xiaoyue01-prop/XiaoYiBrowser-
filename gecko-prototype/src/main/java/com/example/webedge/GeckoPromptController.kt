package com.example.webedge

import android.net.Uri
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PromptDelegate

/**
 * alert / confirm / prompt của trang web trên hộp thoại kính mờ Summit.
 * Chỉ tab đang xem được hiện hộp thoại; tab nền bị bỏ qua (dismiss). Quá 5 hộp thoại đang chờ
 * cũng bị bỏ qua để trang không thể chặn trình duyệt bằng vòng lặp alert.
 * Các loại prompt khác (chọn tệp, xác thực, popup...) chưa xử lý: giữ nguyên hành vi cũ (bỏ qua).
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
}
