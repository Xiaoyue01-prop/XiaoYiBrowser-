#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import json, os, sys

G, Y, R, B, N = "\033[32m", "\033[33m", "\033[31m", "\033[34m", "\033[0m"

def die(msg):
    print(R + "[LỖI] " + N + msg); sys.exit(1)

if not os.path.isfile("settings.gradle.kts") or not os.path.isdir("gecko-prototype"):
    die("Chưa đứng trong thư mục project (thiếu settings.gradle.kts hoặc gecko-prototype/). Hãy cd vào thư mục project rồi chạy lại.")

ACT  = "gecko-prototype/src/main/java/com/example/summitgecko/GeckoPrototypeActivity.kt"
MENU = "gecko-prototype/src/main/java/com/example/webedge/GeckoMenuController.kt"
for p in (ACT, MENU):
    if not os.path.isfile(p): die("Không thấy file: " + p)

def read_lines(p):
    with open(p, encoding="utf-8") as f: return f.read().split("\n")
def write_lines(p, ls):
    with open(p, "w", encoding="utf-8") as f: f.write("\n".join(ls))
def indent_of(s): return s[:len(s) - len(s.lstrip())]

def find_line(ls, s):
    for i, l in enumerate(ls):
        if l.strip() == s: return i
    return -1

def find_block(ls, block):
    n = len(block)
    for i in range(len(ls) - n + 1):
        if all(ls[i + j].strip() == block[j] for j in range(n)): return i
    return -1

def reindent(base, new_lines):
    ind = [len(l) - len(l.lstrip()) for l in new_lines if l.strip()]
    mn = min(ind) if ind else 0
    out = []
    for l in new_lines:
        if not l.strip(): out.append("")
        else: out.append(base + " " * ((len(l) - len(l.lstrip())) - mn) + l.strip())
    return out

results = []
def rec(step, st): results.append((step, st))

def op_delete_line(ls, s, step):
    i = find_line(ls, s)
    if i < 0: rec(step, "already"); return
    del ls[i]; rec(step, "ok")

def op_replace_line(ls, old, new, step):
    i = find_line(ls, old)
    if i >= 0:
        ls[i] = indent_of(ls[i]) + new; rec(step, "ok"); return
    rec(step, "already" if find_line(ls, new) >= 0 else "missing")

def op_delete_block(ls, block, step):
    i = find_block(ls, block)
    if i < 0: rec(step, "already"); return
    del ls[i:i + len(block)]; rec(step, "ok")

def op_replace_block(ls, old, new, step):
    i = find_block(ls, old)
    if i >= 0:
        ls[i:i + len(old)] = reindent(indent_of(ls[i]), new); rec(step, "ok"); return
    rec(step, "already" if find_block(ls, new) >= 0 else "missing")

def op_insert_after_block(ls, anchor, new, step):
    i = find_block(ls, anchor)
    if i < 0:
        rec(step, "already" if find_block(ls, new) >= 0 else "missing"); return
    ls[i + len(anchor):i + len(anchor)] = reindent(indent_of(ls[i]), new); rec(step, "ok")

def op_insert_after_line(ls, anchor, new, step):
    i = find_line(ls, anchor)
    if i < 0: rec(step, "missing"); return
    if find_block(ls, new) >= 0: rec(step, "already"); return
    ls[i + 1:i + 1] = reindent(indent_of(ls[i]), new); rec(step, "ok")

# ============ 1. GeckoPrototypeActivity.kt: bỏ Below + Native, chỉ còn Edge JS ============
act = read_lines(ACT)

op_delete_line(act, "private var mode = 0", "ACT: xoá biến mode")
op_delete_line(act, "private val isEdge: Boolean get() = mode != 0", "ACT: xoá isEdge")
op_delete_line(act, 'private val MODE_NAMES = arrayOf("Below", "Edge JS", "Edge Native")', "ACT: xoá MODE_NAMES")
op_replace_line(act, "val top = if (fullscreen || isEdge) 0 else statusTop", "val top = 0", "ACT: luôn tràn dưới status bar")
op_delete_line(act, "val feedInset = mode == 1 && !fullscreen", "ACT: xoá feedInset")
op_delete_line(act, "val feedToolbar = mode == 2 && !fullscreen", "ACT: xoá feedToolbar")
op_replace_line(act,
    "surfaceView.setSafeInsets(if (feedInset) statusTop else 0, 0, reserve, 0)",
    "surfaceView.setSafeInsets(if (fullscreen) 0 else statusTop, 0, reserve, 0)",
    "ACT: setSafeInsets kiểu Edge JS")
op_replace_line(act,
    "surfaceView.setDynamicToolbarMaxHeight(if (feedToolbar) statusTop else 0)",
    "surfaceView.setDynamicToolbarMaxHeight(0)",
    "ACT: bỏ thí nghiệm dynamic toolbar")

op_replace_block(act,
"""if (!isEdge) {
sampleColor()
} else {
val c = probeColor
if (probeHasFixed && c != null) {
setStripSolid(c)
copyState = "probe colour"
updateInfo()
} else {
sampleGlass()
}
}""".split("\n"),
"""val c = probeColor
if (probeHasFixed && c != null) {
    setStripSolid(c)
    copyState = "probe colour"
    updateInfo()
} else {
    sampleGlass()
}""".split("\n"),
"ACT: sampleTop chỉ còn nhánh Edge JS")

op_replace_line(act,
    'info.text = "GeckoView 159 · ${MODE_NAMES[mode]} · bar ${statusTop}px · copy: $copyState\\n" +',
    'info.text = "GeckoView 159 · Edge JS · bar ${statusTop}px · copy: $copyState\\n" +',
    "ACT: updateInfo bỏ MODE_NAMES")
op_replace_line(act, "if (isEdge) sampleTop()", "sampleTop()", "ACT: handleProbe gọi thẳng sampleTop")
op_delete_line(act, "override val edgeModeLabel: String get() = MODE_NAMES[mode]", "ACT: xoá edgeModeLabel (host)")
op_delete_block(act,
"""override fun cycleEdgeMode() {
mode = (mode + 1) % MODE_NAMES.size
resetProbeState(); updateLayout(); session.reload()
}""".split("\n"), "ACT: xoá cycleEdgeMode (host)")

# ============ 2. Bottom nav lift (phần native) ============
op_insert_after_line(act, "val payload = message as? JSONObject",
"""// Bottom-lift poll của content script: trả lời ngay, không log để khỏi spam logcat.
if (payload != null && payload.optString("type") == "bottomQuery") {
    return GeckoResult.fromValue(bottomPayload())
}""".split("\n"),
"ACT: thêm fast-path bottomQuery trong onMessage")

op_insert_after_block(act,
"""private fun refreshNavButtons() {
if (::toolbar.isInitialized) toolbar.updateNav()
}""".split("\n"),
"""
/** Khoảng (px CSS) từ đáy trang tới đỉnh viên thuốc địa chỉ, để nâng thanh đáy của website. */
private fun bottomLiftCss(): Int {
    if (fullscreen || !::toolbar.isInitialized) return 0
    val d = resources.displayMetrics.density
    val half = Constants.BOTTOM_BAR_HEIGHT_DP * d / 2f
    val barTop = maxOf(insetBottom, imeBottom) + 10 * d + half +
        half * (if (toolbar.barHidden) 0.62f else 1f) + 6 * d
    return (maxOf(0f, barTop - imeBottom) / d).toInt()
}

private fun bottomPayload(): JSONObject =
    JSONObject().put("type", "bottom").put("px", bottomLiftCss())""".split("\n"),
"ACT: thêm bottomLiftCss + bottomPayload")

write_lines(ACT, act)

# ============ 3. GeckoMenuController.kt: bỏ mục "Chế độ Edge" ============
menu = read_lines(MENU)
op_delete_line(menu, "val edgeModeLabel: String", "MENU: xoá edgeModeLabel (interface)")
op_delete_line(menu, "fun cycleEdgeMode()", "MENU: xoá cycleEdgeMode (interface)")
op_delete_line(menu, 'addSetting("Chế độ Edge", host.edgeModeLabel) { host.cycleEdgeMode(); showSettings() }', "MENU: xoá dòng cài đặt Chế độ Edge")
write_lines(MENU, menu)

# ============ 4. Bottom nav lift (phần JS trong probe) ============
JS_MARK = "SUMMIT_BOTTOM_LIFT_PATCH_v1"
JS_BLOCK = '''// >>> SUMMIT_BOTTOM_LIFT_PATCH_v1 >>>
// Summit: nang thanh dieu huong day cua website len tren vien thuoc dia chi.
// Gia tri khoang chua duoc hoi native moi 300ms (bottomQuery -> bottomLiftCss).
(function () {
  if (window.top !== window) return;
  if (window.__summitBottomLift) return;
  window.__summitBottomLift = true;

  var reserve = 0;
  var lifted = [];
  var originals = new WeakMap();
  var docEl = document.documentElement;
  var htmlPadOriginal = null;

  function candidates() {
    var out = [];
    var els = document.getElementsByTagName('*');
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      var cs = getComputedStyle(el);
      if (cs.position !== 'fixed' && cs.position !== 'sticky') continue;
      var r = el.getBoundingClientRect();
      if (r.height < 8 || r.width < 8) continue;
      if (window.innerHeight - r.bottom < 24) out.push(el);
    }
    return out;
  }

  function apply() {
    var i, el, orig;
    for (i = 0; i < lifted.length; i++) {
      el = lifted[i];
      orig = originals.get(el);
      if (orig === undefined || orig === '') el.style.removeProperty('bottom');
      else el.style.setProperty('bottom', orig, 'important');
    }
    lifted = [];
    if (reserve > 0) {
      if (htmlPadOriginal === null) htmlPadOriginal = docEl.style.paddingBottom;
      docEl.style.setProperty('padding-bottom', reserve + 'px', 'important');
    } else if (htmlPadOriginal !== null) {
      if (htmlPadOriginal === '') docEl.style.removeProperty('padding-bottom');
      else docEl.style.setProperty('padding-bottom', htmlPadOriginal, 'important');
      htmlPadOriginal = null;
    }
    if (reserve <= 0) return;
    var list = candidates();
    for (i = 0; i < list.length; i++) {
      el = list[i];
      if (!originals.has(el)) originals.set(el, el.style.bottom);
      var csBottom = parseFloat(getComputedStyle(el).bottom);
      var base = isNaN(csBottom) ? 0 : csBottom;
      el.style.setProperty('bottom', (base + reserve) + 'px', 'important');
      lifted.push(el);
    }
  }

  var timer = null;
  function schedule() {
    if (timer) return;
    timer = setTimeout(function () { timer = null; apply(); }, 250);
  }

  function query() {
    if (document.visibilityState !== 'visible') return;
    try {
      browser.runtime.sendNativeMessage('browser', { type: 'bottomQuery' })
        .then(function (m) {
          var px = (m && typeof m.px === 'number') ? (m.px | 0) : 0;
          if (px !== reserve) { reserve = px; apply(); }
        })
        .catch(function () {});
    } catch (e) {}
  }

  query();
  setInterval(query, 300);
  window.addEventListener('resize', schedule);
  var mo = new MutationObserver(schedule);
  mo.observe(docEl, { childList: true, subtree: true });
})();
// <<< SUMMIT_BOTTOM_LIFT_PATCH_v1 <<<'''

probe_dir = "gecko-prototype/src/main/assets/summit_probe"
targets = []
manifest = os.path.join(probe_dir, "manifest.json")
if os.path.isfile(manifest):
    try:
        m = json.load(open(manifest, encoding="utf-8"))
        for cs in (m.get("content_scripts") or []):
            for js in (cs.get("js") or []):
                p = os.path.join(probe_dir, js)
                if os.path.isfile(p): targets.append(p)
    except Exception:
        pass
if not targets and os.path.isdir(probe_dir):
    for f in sorted(os.listdir(probe_dir)):
        if f.endswith(".js") and "background" not in f.lower():
            targets.append(os.path.join(probe_dir, f))

if targets:
    for t in targets:
        src = open(t, encoding="utf-8").read()
        if JS_MARK in src:
            rec("JS bottom-lift -> " + t, "already")
        else:
            open(t, "a", encoding="utf-8").write("\n\n" + JS_BLOCK + "\n")
            rec("JS bottom-lift -> " + t, "ok")
else:
    fb = "gecko-prototype/summit_probe_bottom_lift.js"
    open(fb, "w", encoding="utf-8").write(JS_BLOCK + "\n")
    rec("KHÔNG tìm thấy content script của probe. Đã ghi " + fb + " — bồ tự dán nội dung vào file JS của probe", "warn")

# ============ Tổng kết ============
print()
print(B + "===== KẾT QUẢ PATCH =====" + N)
bad = 0
for step, st in results:
    if st == "ok": print(G + "  [OK]   " + N + step)
    elif st == "already": print(G + "  [OK]   " + N + step + " (đã áp trước rồi)")
    elif st == "warn": print(Y + "  [CHÚ Ý] " + N + step)
    else:
        print(R + "  [LỖI]  " + N + step + " (không khớp code hiện tại)"); bad += 1
print()
if bad == 0:
    print(G + "Thành công! Xem lại thay đổi bằng: git diff" + N)
    print("Sau đó push để GitHub Actions build:")
    print("  git add -A")
    print('  git commit -m "Summit Gecko: Edge JS duy nhất + nang thanh day website"')
    print("  git push")
else:
    print(R + "Có bước không khớp — ĐỪNG push. Copy nguyên màn hình kết quả này gửi lại để mình chỉnh." + N)
