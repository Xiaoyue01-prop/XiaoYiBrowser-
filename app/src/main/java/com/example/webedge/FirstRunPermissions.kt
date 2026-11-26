package com.example.webedge

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Xin gộp các quyền runtime một lần duy nhất khi mở app lần đầu: thông báo, camera, micro, vị trí.
 * Android vẫn hiện từng hộp thoại hệ thống liên tiếp (mỗi nhóm quyền một hộp), nhưng người dùng làm xong
 * ngay từ đầu thay vì bị hỏi rải rác khi tải file, xem video, vào trang cần camera...
 *
 * Các nơi xin quyền theo ngữ cảnh (Downloads, WebMedia, WebPermissionController) vẫn giữ nguyên làm phương án dự phòng
 * cho trường hợp người dùng từ chối ở lần đầu.
 */
class FirstRunPermissions(
    private val activity: ComponentActivity,
    private val store: Store
) {
    private val launcher = (activity as? BiometricBrowserActivity)?.firstRunLauncher
        ?: activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    /** Gọi sau khi UI đã dựng xong. Chỉ hỏi một lần; từ lần sau trở đi không làm gì. */
    fun requestIfFirstRun() {
        if (store.firstRunPermissionsAsked) return
        // Đánh dấu ngay khi bắt đầu hỏi để nếu app bị kill giữa chừng cũng không hỏi lại lần nữa.
        store.firstRunPermissionsAsked = true
        val missing = PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) launcher.launch(missing.toTypedArray())
    }

    private companion object {
        // Vị trí: xin cả COARSE và FINE trong cùng một lần để hệ thống chỉ hiện một hộp thoại có chọn "Chính xác/Xấp xỉ".
        val PERMISSIONS = listOf(
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }
}
