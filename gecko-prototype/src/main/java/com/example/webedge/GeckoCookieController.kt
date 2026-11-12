package com.example.webedge

/**
 * Type alias: GeckoView dùng GeckoCookieController thay vì WebKit's CookieManager.
 * File này chỉ re-export để GeckoDownloadController compile sạch.
 */
typealias GeckoCookieController = org.mozilla.geckoview.CookieController
