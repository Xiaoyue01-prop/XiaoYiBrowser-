package com.example.webedge

import android.util.LruCache
import android.webkit.WebView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.WeakHashMap
import kotlin.coroutines.resume

/**
 * Nguồn dịch cho tính năng dịch trang (hiện là dịch trên máy bằng ML Kit, xem [OnDeviceBackend]). Tách riêng để
 * sau này đổi nguồn khác mà không phải sửa phần duyệt DOM / UI.
 */
internal interface TranslateBackend {
    val maxBatchChars: Int
    val maxBatchItems: Int
    val concurrency: Int

    /**
     * Gọi một lần trước khi dịch trang: backend có thể nhận diện ngôn ngữ nguồn, tải gói model...
     * [status] để báo tiến trình cho người dùng. Trả false nếu trang đã ở ngôn ngữ đích (không cần dịch).
     */
    suspend fun prepare(sample: String, target: String, status: (String) -> Unit): Boolean = true

    /** Dịch [src] sang [target]; PHẢI trả về đúng số phần tử. Lỗi ném [TranslateApiException]. */
    suspend fun translateBatch(src: List<String>, target: String): List<String>
}

/** Chia lô + cache RAM + chạy song song giới hạn cho một [TranslateBackend]. Cache không ghi đĩa (an toàn cho tab riêng tư). */
internal object BatchTranslator {
    private val cache = LruCache<String, String>(5_000)

    /** [onResult]: `indices[n]` là vị trí trong [texts] của `translated[n]`. Lỗi RATE/AUTH/QUOTA dừng sớm. */
    suspend fun translateAll(
        backend: TranslateBackend,
        target: String,
        texts: List<String>,
        onResult: (indices: List<Int>, translated: List<String>) -> Unit
    ) {
        val pending = ArrayList<Int>()
        val hitIdx = ArrayList<Int>()
        val hitText = ArrayList<String>()
        texts.forEachIndexed { i, t ->
            val hit = cache.get("$target|$t")
            if (hit != null) { hitIdx += i; hitText += hit } else pending += i
        }
        if (hitIdx.isNotEmpty()) onResult(hitIdx, hitText)
        if (pending.isEmpty()) return

        val batches = ArrayList<List<Int>>()
        var cur = ArrayList<Int>()
        var chars = 0
        for (i in pending) {
            val len = texts[i].length
            if (cur.isNotEmpty() && (chars + len > backend.maxBatchChars || cur.size >= backend.maxBatchItems)) {
                batches += cur; cur = ArrayList(); chars = 0
            }
            cur += i; chars += len
        }
        if (cur.isNotEmpty()) batches += cur

        val gate = Semaphore(backend.concurrency)
        var firstError: TranslateApiException? = null
        var fatal = false
        coroutineScope {
            batches.map { idxs ->
                async {
                    gate.withPermit {
                        if (fatal) return@withPermit
                        val src = idxs.map { texts[it] }
                        try {
                            val out = backend.translateBatch(src, target)
                            src.forEachIndexed { n, s -> cache.put("$target|$s", out[n]) }
                            onResult(idxs, out)
                        } catch (e: TranslateApiException) {
                            if (firstError == null) firstError = e
                            if (e.kind != TranslateApiException.Kind.OTHER) fatal = true
                        }
                    }
                }
            }.forEach { it.await() }
        }
        firstError?.let { throw it }
    }
}

/**
 * Dịch toàn trang tại chỗ: Kotlin chủ động chạy JS qua evaluateJavascript để gom các text node, dịch,
 * rồi ghi ngược vào đúng node. KHÔNG thêm thao tác nào vào cầu nối trang → app (trang web không thể tự gọi
 * dịch hay đọc kết quả), nên không nới bề mặt tấn công của WebMessageListener.
 *
 * Giới hạn bản đầu: chỉ dịch nội dung có sẵn lúc bấm (chưa theo dõi nội dung tải thêm khi cuộn),
 * không dịch thuộc tính (placeholder, alt, title) và bỏ qua code/pre/ô nhập.
 */
internal class PageTranslator(
    private val scope: CoroutineScope,
    private val web: () -> WebView,
    private val toast: (String) -> Unit,
    private val backend: TranslateBackend = OnDeviceBackend,
    private val target: String = "vi"
) {
    private class State {
        var job: Job? = null
        var applied = false
        var showing = false
    }

    private val states = WeakHashMap<WebView, State>()

    /** Chữ trên mục menu theo trạng thái trang đang xem. */
    fun menuLabel(): String {
        val st = states[web()] ?: return "Dịch sang tiếng Việt"
        return when {
            st.job?.isActive == true -> "Đang dịch… (chạm để dừng)"
            st.applied && st.showing -> "Hiện bản gốc"
            st.applied -> "Hiện bản dịch"
            else -> "Dịch sang tiếng Việt"
        }
    }

    /** Trang vừa chuyển/tải lại: document mới nên trạng thái JS cũ đã mất. */
    fun onNavigation(view: WebView) {
        states.remove(view)?.job?.cancel()
    }

    fun toggle() {
        val view = web()
        if (HomePage.isHome(view.url)) {
            toast("Không dịch được trang này")
            return
        }
        val st = states.getOrPut(view) { State() }
        when {
            st.job?.isActive == true -> {
                st.job?.cancel()
                st.job = null
                if (!st.applied) resetJs(view)
                toast("Đã dừng dịch")
            }
            st.applied -> {
                st.showing = !st.showing
                view.evaluateJavascript("window['$JS_KEY']&&window['$JS_KEY'].show(${st.showing})", null)
            }
            else -> begin(view, st)
        }
    }

    private fun begin(view: WebView, st: State) {
        st.job = scope.launch {
            try {
                val raw = evalJs(view, COLLECT_JS)
                val collected = (JSONTokener(raw).nextValue() as? String)?.let { JSONObject(it) }
                val start = collected?.optInt("start") ?: 0
                val arr = collected?.optJSONArray("texts")
                val texts = List(arr?.length() ?: 0) { arr!!.optString(it) }
                if (texts.isEmpty()) {
                    resetJs(view)
                    toast("Không có nội dung để dịch")
                    return@launch
                }
                if (TranslateApi.looksVietnamese(texts.take(60).joinToString(" "))) {
                    resetJs(view)
                    toast("Trang này đã là tiếng Việt")
                    return@launch
                }
                toast("Đang dịch…")
                if (!backend.prepare(texts.take(60).joinToString(" "), target, toast)) {
                    resetJs(view)
                    toast("Trang này đã là tiếng Việt")
                    return@launch
                }
                st.showing = true
                BatchTranslator.translateAll(backend, target, texts) { indices, results ->
                    if (states[view] !== st) return@translateAll
                    st.applied = true
                    view.evaluateJavascript(
                        "window['$JS_KEY']&&window['$JS_KEY'].apply($start,${JSONArray(indices)},${JSONArray(results)})",
                        null
                    )
                }
                if (states[view] === st) toast("Đã dịch xong")
            } catch (e: CancellationException) {
                throw e
            } catch (e: TranslateApiException) {
                if (states[view] !== st) return@launch
                if (!st.applied) resetJs(view)
                toast(
                    when (e.kind) {
                        TranslateApiException.Kind.RATE -> "Dịch đang bị giới hạn (${e.message}), thử lại sau ít phút"
                        TranslateApiException.Kind.NETWORK -> e.message.orEmpty()
                        TranslateApiException.Kind.BLOCKED -> "Yêu cầu dịch bị từ chối (${e.message})"
                        else -> "Dịch lỗi: ${e.message}"
                    }
                )
            } catch (e: JSONException) {
                resetJs(view)
                toast("Không đọc được nội dung trang")
            }
        }
    }

    /** Xóa trạng thái JS để lần bấm sau gom lại từ đầu (các node đã đánh dấu "đã thấy" sẽ không bị bỏ sót). */
    private fun resetJs(view: WebView) {
        view.evaluateJavascript("try{delete window['$JS_KEY']}catch(e){}", null)
    }

    private suspend fun evalJs(view: WebView, js: String): String =
        suspendCancellableCoroutine { cont -> view.evaluateJavascript(js) { cont.resume(it ?: "null") } }

    private companion object {
        const val JS_KEY = "__summitTr"

        /**
         * Khởi tạo kho trạng thái (một lần/document) và gom các text node CHƯA gom, node gần màn hình trước.
         * Trả về chuỗi JSON {start, texts}; texts[i] ứng với node số start+i. Không dùng ký tự đô-la trong
         * chuỗi này (Kotlin sẽ coi là template).
         */
        const val COLLECT_JS = """
(function(){
var K='__summitTr', S=window[K];
if(!S){
  S={nodes:[],orig:[],lead:[],trail:[],tr:[],seen:new WeakSet(),showing:true};
  S.apply=function(start,idx,txt){
    for(var n=0;n<idx.length;n++){
      var i=start+idx[n], node=S.nodes[i], t=txt[n];
      if(!node||!node.isConnected||typeof t!=='string'||!t) continue;
      S.tr[i]=t;
      if(S.showing&&node.nodeValue===S.orig[i]) node.nodeValue=S.lead[i]+t+S.trail[i];
    }
  };
  S.show=function(on){
    S.showing=!!on;
    for(var i=0;i<S.nodes.length;i++){
      var node=S.nodes[i], t=S.tr[i];
      if(!node||!node.isConnected||typeof t!=='string') continue;
      var tv=S.lead[i]+t+S.trail[i];
      if(on){ if(node.nodeValue===S.orig[i]) node.nodeValue=tv; }
      else { if(node.nodeValue===tv) node.nodeValue=S.orig[i]; }
    }
  };
  Object.defineProperty(window,K,{value:S,configurable:true,enumerable:false});
}
var root=document.body;
if(!root) return JSON.stringify({start:0,texts:[]});
var SKIP={SCRIPT:1,STYLE:1,NOSCRIPT:1,TEXTAREA:1,CODE:1,PRE:1,SVG:1,CANVAS:1,TEMPLATE:1,IFRAME:1,OBJECT:1,INPUT:1,SELECT:1,OPTION:1,KBD:1,SAMP:1};
var w=document.createTreeWalker(root,NodeFilter.SHOW_TEXT,null);
var near=[],far=[],vh=window.innerHeight||800,n;
while((n=w.nextNode())){
  if(S.seen.has(n)) continue;
  var el=n.parentElement;
  if(!el||SKIP[el.tagName.toUpperCase()]||el.isContentEditable) continue;
  if(el.closest('[translate=no],.notranslate,pre,code')) continue;
  var v=n.nodeValue; if(!v) continue;
  var core=v.trim();
  if(core.length<2||!/\p{L}/u.test(core)) continue;
  var r=el.getBoundingClientRect();
  var item={n:n,v:v,core:core};
  if(r.width>0&&r.height>0&&r.bottom>-vh&&r.top<vh*3) near.push(item); else far.push(item);
}
var all=near.concat(far), out=[], base=S.nodes.length, total=0;
for(var k=0;k<all.length&&out.length<3000&&total<200000;k++){
  var it=all[k], lead=it.v.slice(0,it.v.indexOf(it.core)), trail=it.v.slice(lead.length+it.core.length);
  S.seen.add(it.n); S.nodes.push(it.n); S.orig.push(it.v); S.lead.push(lead); S.trail.push(trail);
  out.push(it.core.replace(/\s+/g,' ')); total+=it.core.length;
}
return JSON.stringify({start:base,texts:out});
})()
"""
    }
}
