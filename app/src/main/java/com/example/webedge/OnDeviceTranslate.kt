package com.example.webedge

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Chờ một [Task] của Google Play services mà không cần thêm thư viện coroutines-play-services. */
private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}

/** Nhận diện ngôn ngữ ngay trên máy (ML Kit), không gửi văn bản đi đâu. */
internal object OnDeviceLanguageId {
    /** Trả mã BCP-47 (vd "en", "ja") hoặc null nếu không chắc. */
    suspend fun detect(sample: String): String? {
        val client = LanguageIdentification.getClient()
        return try {
            client.identifyLanguage(sample.take(1_500)).awaitResult().takeIf { it != "und" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } finally {
            client.close()
        }
    }
}

/** Một cặp ngôn ngữ dịch trên máy (ML Kit Translation, cùng họ công nghệ dịch trên máy như Firefox Translations). */
private class OnDeviceSession(private val translator: Translator) : TranslateBackend {
    override val maxBatchChars = 3_000
    override val maxBatchItems = 40
    override val concurrency = 1

    override suspend fun translateBatch(src: List<String>, target: String): List<String> =
        try {
            // Dịch từng đoạn một: bảo đảm đúng số phần tử và không phụ thuộc cách model xử lý dấu xuống dòng.
            src.map { translator.translate(it).awaitResult() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw TranslateApiException(TranslateApiException.Kind.OTHER, "Dịch trên máy lỗi: ${e.message}")
        }

    fun close() = translator.close()
}

/**
 * Dịch TRÊN MÁY bằng ML Kit (miễn phí, không giới hạn tốc độ, văn bản trang không rời điện thoại).
 * Lần đầu với mỗi ngôn ngữ nguồn phải tải một gói model (vài chục MB). Ngôn ngữ ngoài 59 ngôn ngữ ML Kit
 * hỗ trợ thì báo "chưa hỗ trợ", không dùng dịch trực tuyến.
 */
internal object OnDeviceBackend : TranslateBackend {
    @Volatile private var session: OnDeviceSession? = null

    override val maxBatchChars = 3_000
    override val maxBatchItems = 40
    override val concurrency = 1

    override suspend fun prepare(sample: String, target: String, status: (String) -> Unit): Boolean {
        session?.close()
        session = null

        val srcTag = OnDeviceLanguageId.detect(sample)
            ?: throw TranslateApiException(TranslateApiException.Kind.OTHER, "Không nhận diện được ngôn ngữ của trang")
        if (srcTag.substringBefore('-') == target) return false

        val mlSrc = TranslateLanguage.fromLanguageTag(srcTag)
        val mlTarget = TranslateLanguage.fromLanguageTag(target)
        if (mlSrc == null || mlTarget == null) {
            throw TranslateApiException(TranslateApiException.Kind.OTHER, "Chưa hỗ trợ dịch ngôn ngữ này ($srcTag)")
        }

        val translator = Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(mlSrc).setTargetLanguage(mlTarget).build()
        )
        try {
            status("Đang chuẩn bị gói dịch (lần đầu cần tải vài chục MB)…")
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).awaitResult()
        } catch (e: CancellationException) {
            translator.close()
            throw e
        } catch (e: Exception) {
            translator.close()
            throw TranslateApiException(TranslateApiException.Kind.NETWORK, "Không tải được gói dịch trên máy, kiểm tra kết nối")
        }
        session = OnDeviceSession(translator)
        return true
    }

    override suspend fun translateBatch(src: List<String>, target: String): List<String> {
        val s = session ?: throw TranslateApiException(TranslateApiException.Kind.OTHER, "Gói dịch chưa sẵn sàng")
        return s.translateBatch(src, target)
    }
}
