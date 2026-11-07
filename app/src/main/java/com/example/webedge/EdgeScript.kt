package com.example.webedge

import android.content.Context

/**
 * Script chạy trong trang (assets/edge_script.js). Việc của nó: (1) khi không tràn viền thì đọc màu mép trên
 * để tô dải thanh trạng thái; (2) khi tràn viền mà trang không khai báo viewport-fit=cover ([COVER_JS]) thì
 * hạ header cố định/sticky xuống dưới thanh trạng thái; (3) nâng thanh
 * cố định ở đáy trang lên trên viên thuốc địa chỉ. Trang có cover thì tự lo bằng env(safe-area-inset-*).
 *
 * File JS chứa các placeholder %FULL%, %INSET%, %BOTTOM%, %COMPACT%, %PILL_METRICS% để Activity thay số.
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
    // Chỉ ghi nhận đã báo khi cầu nối thật sự nhận được, để lần kiểm tra sau còn thử lại nếu cầu nối chưa sẵn sàng.
    try { EdgeBridge.setCover(c); last = c; } catch (e) {}
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
  window.addEventListener('load', function(){ check(true); }, {once: true});
  check(false);
})();
"""
}
