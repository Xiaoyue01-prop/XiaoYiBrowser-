package com.example.webedge

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Xử lý yêu cầu quyền của trang web: camera/micro và vị trí.
 * Hiện hộp thoại kính mờ xin phép người dùng, sau đó xin quyền hệ thống nếu máy chưa cấp.
 *
 * Phải được tạo cùng lúc với Activity (là property của Activity) vì đăng ký ActivityResult launcher.
 */
class WebPermissionController(
    private val activity: ComponentActivity,
    private val dialog: () -> GlassDialogController,
    private val isActivityDestroyed: () -> Boolean,
    private val isPopupShowing: () -> Boolean
) {
    private var pendingRequest: PermissionRequest? = null
    private var pendingResources: Array<String> = emptyArray()
    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null

    private val mediaLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> finishMediaRequest() }

    private val geoLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> finishGeoRequest() }

    private fun hasRuntimePermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED

    // ---------------- Camera / micro ----------------

    fun requestMedia(request: PermissionRequest) {
        if (isActivityDestroyed()) {
            request.deny()
            return
        }
        val supported = request.resources.filter {
            it == PermissionRequest.RESOURCE_VIDEO_CAPTURE ||
                it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
        }.toTypedArray()
        if (supported.isEmpty()) {
            request.deny()
            return
        }
        pendingRequest?.let { previous ->
            if (previous !== request) previous.deny()
        }
        pendingRequest = request
        pendingResources = supported
        val requestedLabels = buildList {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in supported) add("camera")
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in supported) add("micro")
        }.joinToString(" và ")
        val origin = request.origin.host ?: request.origin.toString()
        // Hộp thoại kính mờ nằm trong cửa sổ chính; cửa sổ popup đăng nhập sẽ che nó nên từ chối luôn.
        if (isPopupShowing()) {
            denyPending()
            return
        }
        dialog().show(
            title = "Quyền trang web",
            message = "$origin muốn sử dụng $requestedLabels. Chỉ cho phép nếu bạn tin tưởng trang này.",
            positive = "Cho phép",
            negative = "Từ chối"
        ) { allowed, _ ->
            if (!allowed) {
                denyPending()
            } else {
                val missing = mutableListOf<String>()
                if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in supported &&
                    !hasRuntimePermission(Manifest.permission.CAMERA)
                ) {
                    missing.add(Manifest.permission.CAMERA)
                }
                if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in supported &&
                    !hasRuntimePermission(Manifest.permission.RECORD_AUDIO)
                ) {
                    missing.add(Manifest.permission.RECORD_AUDIO)
                }
                if (missing.isEmpty()) finishMediaRequest()
                else mediaLauncher.launch(missing.toTypedArray())
            }
        }
    }

    private fun finishMediaRequest() {
        val request = pendingRequest ?: return
        val resources = pendingResources
        pendingRequest = null
        pendingResources = emptyArray()
        val allowed = resources.filter {
            when (it) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> hasRuntimePermission(Manifest.permission.CAMERA)
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> hasRuntimePermission(Manifest.permission.RECORD_AUDIO)
                else -> false
            }
        }.toTypedArray()
        runCatching {
            if (allowed.isEmpty()) request.deny() else request.grant(allowed)
        }
    }

    private fun denyPending() {
        val request = pendingRequest ?: return
        pendingRequest = null
        pendingResources = emptyArray()
        runCatching { request.deny() }
    }

    // ---------------- Vị trí ----------------

    fun requestGeolocation(origin: String, callback: GeolocationPermissions.Callback) {
        if (isActivityDestroyed()) {
            callback.invoke(origin, false, false)
            return
        }
        val host = runCatching { Uri.parse(origin).host }.getOrNull() ?: origin
        if (isPopupShowing()) {
            callback.invoke(origin, false, false)
            return
        }
        dialog().show(
            title = "Vị trí của bạn",
            message = "$host muốn truy cập vị trí của bạn. Vị trí chỉ được cấp cho yêu cầu này.",
            positive = "Cho phép",
            negative = "Từ chối"
        ) { allowed, _ ->
            if (!allowed) {
                callback.invoke(origin, false, false)
            } else {
                pendingGeoOrigin = origin
                pendingGeoCallback = callback
                val missing = listOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ).filterNot(::hasRuntimePermission)
                if (missing.isEmpty()) finishGeoRequest()
                else geoLauncher.launch(missing.toTypedArray())
            }
        }
    }

    private fun finishGeoRequest() {
        val origin = pendingGeoOrigin ?: return
        val callback = pendingGeoCallback
        pendingGeoOrigin = null
        pendingGeoCallback = null
        val allowed = hasRuntimePermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasRuntimePermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        callback?.invoke(origin, allowed, false)
    }

    /** Trả lời mọi yêu cầu còn treo (từ chối) khi Activity bị hủy, để trang không bị đơ. */
    fun cancelAll() {
        denyPending()
        pendingGeoCallback?.invoke(pendingGeoOrigin.orEmpty(), false, false)
        pendingGeoCallback = null
        pendingGeoOrigin = null
    }
}
