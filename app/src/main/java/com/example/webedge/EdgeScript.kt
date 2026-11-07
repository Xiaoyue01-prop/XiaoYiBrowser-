package com.example.webedge

import android.content.Context

/**
 * Script chạy trong trang (assets/edge_script.js). Nó chỉ còn hai việc: đọc màu mép trên của trang để tô
 * dải thanh trạng thái (khi WebView nằm dưới thanh trạng thái) và nâng thanh cố định ở đáy trang lên trên
 * viên thuốc địa chỉ. Việc tràn viền do native quyết định theo từng trang: trang khai báo
 * viewport-fit=cover ([COVER_JS]) thì WebView tràn lên và trang tự lo bằng env(safe-area-inset-*).
 *
 * File JS chứa các placeholder %COVER%, %BOTTOM%, %COMPACT%, %PILL_METRICS% để Activity thay số.
 */
internal object EdgeScript {
    @Volatile private var cached: String? = null

    fun template(context: Context): String = cached ?: context.applicationContext.assets
        .open("edge_script.js").bufferedReader().use { it.readText() }
        .also { cached = it }

    /**
     * Chạy từ đầu tài liệu, chỉ ở khung chính: báo EdgeBridge.setCover(true/false) theo thẻ meta viewport.
     * Chỉ báo khi đã thấy thẻ meta hoặc khi DOM nạp xong, để không báo "false" tạm thời lúc <head> chưa có.
     * Sau khi nạp xong chỉ còn theo dõi <head> (nhỏ) phòng trang đổi meta muộn.
     */
    const val COVER_JS = """
(function(){
  if (window.top !== window || window.__edgeCoverWatch) return;
  window.__edgeCoverWatch = true;
  var last = null, mo = null;
  function check(done){
    var m = document.querySelector('meta[name="viewport" i]');
    if (!m && !done) return;
    var c = !!(m && /viewport-fit\s*=\s*cover/i.test(m.getAttribute('content') || ''));
    if (c === last) return;
    last = c;
    try { EdgeBridge.setCover(c); } catch (e) {}
  }
  function watchHead(){
    if (mo) mo.disconnect();
    if (!document.head) return;
    mo = new MutationObserver(function(){ check(true); });
    mo.observe(document.head, {childList: true, subtree: true, attributes: true, attributeFilter: ['content', 'name']});
  }
  try {
    mo = new MutationObserver(function(){ check(false); });
    mo.observe(document, {childList: true, subtree: true});
  } catch (e) {}
  document.addEventListener('DOMContentLoaded', function(){ check(true); watchHead(); }, {once: true});
  check(false);
})();
"""
}
