package com.example.webedge

import android.content.Context

/**
 * Script chạy trong trang (assets/edge_script.js). Chế độ tràn viền: đẩy thanh menu cố định ở đầu trang
 * xuống dưới thanh trạng thái (hoặc chừa chỗ cho lớp phủ toàn màn hình) và báo màu thanh đó về app để
 * tô dải thanh trạng thái. Chế độ thường: chỉ đọc màu mép trên của trang.
 *
 * File JS chứa các placeholder %INSET%, %MODE%, %BOTTOM%, %COMPACT%, %PILL_METRICS% để Activity thay số.
 */
internal object EdgeScript {
    @Volatile private var cached: String? = null

    fun template(context: Context): String = cached ?: context.applicationContext.assets
        .open("edge_script.js").bufferedReader().use { it.readText() }
        .also { cached = it }
}
