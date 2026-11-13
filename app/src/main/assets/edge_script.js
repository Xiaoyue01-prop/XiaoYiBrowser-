(function(){
window.__edgeInset = %INSET%;
window.__edgeMode = %MODE%;
window.__edgeBottom = %BOTTOM%;
window.__edgeCompact = %COMPACT%;
%PILL_METRICS%
if (window.__edgeStarted) {
  if (window.__edgeRun) window.__edgeRun();
  else if (window.__edgePageBgRun) window.__edgePageBgRun();
  return;
}
window.__edgeStarted = true;

var last = '';
var lastPageBg = '';
var pageBgTimer = null;
var styleCache = new WeakMap();
var rafId = null;

var cvCtx = null;
// Màu hiện đại (color(srgb ...), oklab, hsl...) không khớp rgb() -> nhờ canvas đổi sang RGB
function viaCanvas(c){
  try {
    if (!cvCtx) {
      var cv = document.createElement('canvas');
      cv.width = 1; cv.height = 1;
      cvCtx = cv.getContext('2d', { willReadFrequently: true });
    }
    cvCtx.clearRect(0, 0, 1, 1);
    cvCtx.fillStyle = '#010203';
    cvCtx.fillStyle = c;
    if (cvCtx.fillStyle === '#010203') return null;
    cvCtx.fillRect(0, 0, 1, 1);
    var d = cvCtx.getImageData(0, 0, 1, 1).data;
    if (d[3] / 255 < 0.85) return null;
    return [d[0], d[1], d[2], d[3] / 255];
  } catch (e) { return null; }
}

function rgbOf(c){
  try {
    if (!c || c === 'transparent') return null;
    var m = c.match(/^rgba?\(([^)]+)\)/);
    if (!m) return viaCanvas(c);
    var p = m[1].split(',').map(function(x){ return parseFloat(x); });
    if (p.length < 3 || isNaN(p[0])) return viaCanvas(c);
    var a = p.length > 3 ? p[3] : 1;
    if (a < 0.85) return null;
    return p;
  } catch (e) { return null; }
}

function bgOf(el){
  if (!el) return null;
  if (styleCache.has(el)) {
    var cached = styleCache.get(el);
    return rgbOf(cached.backgroundColor);
  }
  try {
    var style = getComputedStyle(el);
    styleCache.set(el, style);
    return rgbOf(style.backgroundColor);
  } catch (e) { return null; }
}

function isVisible(el){
  try {
    var style = styleCache.get(el) || getComputedStyle(el);
    if (!styleCache.has(el)) styleCache.set(el, style);
    var opacity = parseFloat(style.opacity);
    var zIndex = style.zIndex;
    var display = style.display;
    if (opacity < 0.1) return false;
    if (display === 'none') return false;
    if (zIndex !== 'auto' && parseInt(zIndex) < 0) return false;
    return true;
  } catch (e) { return true; }
}

function pinned(el){
  while (el && el !== document.documentElement && el !== document.body) {
    try {
      var s = styleCache.get(el) || getComputedStyle(el);
      if (!styleCache.has(el)) styleCache.set(el, s);
      if (s.position === 'fixed' || s.position === 'sticky') {
        if (isVisible(el)) return el;
      }
      el = el.parentElement;
    } catch (e) { return null; }
  }
  return null;
}

function climb(el){
  for (; el; el = el.parentElement) {
    var c = bgOf(el);
    if (c) return c;
  }
  return null;
}

// ---- Màu nền canvas của trang: độc lập hoàn toàn với màu header/status bar ----
function gradientBackgroundColor(style){
  try {
    var image = style && style.backgroundImage;
    if (!image || image === 'none' || !/gradient\(/i.test(image)) return null;
    image = image.replace(/url\([^)]*\)/gi, '');
    var matches = image.match(/rgba?\([^)]*\)|hsla?\([^)]*\)|#[0-9a-f]{3,8}/gi) || [];
    var colors = [];
    for (var i = 0; i < matches.length; i++) {
      var c = rgbOf(matches[i]);
      if (c) colors.push(c);
    }
    if (!colors.length) return null;
    var total = [0, 0, 0];
    colors.forEach(function(c){
      total[0] += c[0];
      total[1] += c[1];
      total[2] += c[2];
    });
    return [
      total[0] / colors.length,
      total[1] / colors.length,
      total[2] / colors.length,
      1
    ];
  } catch (e) { return null; }
}

function pageCanvasColor(){
  try {
    var body = document.body;
    var root = document.documentElement;

    // Theo quy tắc canvas của CSS, nền body được ưu tiên; nếu body trong suốt thì dùng html.
    var bodyStyle = body ? getComputedStyle(body) : null;
    var rootStyle = root ? getComputedStyle(root) : null;
    var bodyColor = bodyStyle
      ? (rgbOf(bodyStyle.backgroundColor) || gradientBackgroundColor(bodyStyle)) : null;
    var rootColor = rootStyle
      ? (rgbOf(rootStyle.backgroundColor) || gradientBackgroundColor(rootStyle)) : null;
    function isLightNeutral(c){
      if (!c) return false;
      var rr = c[0] / 255, gg = c[1] / 255, bb = c[2] / 255;
      var hi = Math.max(rr, gg, bb), lo = Math.min(rr, gg, bb);
      var lum = 0.2126 * rr + 0.7152 * gg + 0.0722 * bb;
      var sat = hi <= 0 ? 0 : (hi - lo) / hi;
      return lum >= 0.68 && sat < 0.24;
    }
    if (bodyColor && !isLightNeutral(bodyColor)) return bodyColor;
    if (!bodyColor && rootColor && !isLightNeutral(rootColor)) return rootColor;

    // Một số SPA để body trắng/trong suốt nhưng đặt nền thật trên app/root container.
    // Lấy mẫu ở vùng nội dung, bỏ qua header/footer fixed để không nhầm màu thanh điều hướng.
    if (!document.elementsFromPoint) return null;
    var w = window.innerWidth, h = window.innerHeight;
    if (w < 2 || h < 2) return null;

    var xs = [0.18, 0.5, 0.82];
    var ys = [0.38, 0.58, 0.76];
    var buckets = Object.create(null);

    for (var yi = 0; yi < ys.length; yi++) {
      for (var xi = 0; xi < xs.length; xi++) {
        var stack = document.elementsFromPoint(w * xs[xi], h * ys[yi]);
        var picked = null;
        for (var si = 0; si < stack.length && !picked; si++) {
          var el = stack[si];
          while (el && el !== body && el !== root) {
            try {
              var cs = getComputedStyle(el);
              var op = parseFloat(cs.opacity);
              if (cs.display !== 'none' && !(op < 0.8) &&
                  cs.position !== 'fixed' && cs.position !== 'sticky') {
                var rect = el.getBoundingClientRect();
                if (rect.width >= w * 0.55 && rect.height >= h * 0.20) {
                  var c = rgbOf(cs.backgroundColor) || gradientBackgroundColor(cs);
                  if (c) {
                    picked = {
                      color: c,
                      area: Math.min(1, (rect.width * rect.height) / (w * h))
                    };
                    break;
                  }
                }
              }
            } catch (e) {}
            el = el.parentElement;
          }
        }

        if (picked) {
          var key = Math.round(picked.color[0]) + ',' +
                    Math.round(picked.color[1]) + ',' +
                    Math.round(picked.color[2]);
          var bucket = buckets[key];
          if (!bucket) {
            bucket = buckets[key] = { count: 0, area: 0, color: picked.color };
          }
          bucket.count++;
          bucket.area = Math.max(bucket.area, picked.area);
        }
      }
    }

    var best = null, bestScore = -1;
    Object.keys(buckets).forEach(function(key){
      var item = buckets[key];
      var score = item.count * 10 + item.area;
      if (score > bestScore) { best = item; bestScore = score; }
    });
    // Chỉ để sample container ghi đè body trắng khi nhiều điểm cùng xác nhận một màu nền lớn.
    if (best && best.count >= 2) return best.color;
    return bodyColor || rootColor || (best ? best.color : null);
  } catch (e) { return null; }
}

function reportPageBackground(color){
  var key = color
    ? Math.round(color[0]) + ',' + Math.round(color[1]) + ',' + Math.round(color[2])
    : 'none';
  if (key === lastPageBg) return;
  lastPageBg = key;
  try {
    if (!color) EdgeBridge.clearPageBackground();
    else EdgeBridge.setPageBackground(
      Math.round(color[0]), Math.round(color[1]), Math.round(color[2])
    );
  } catch (e) {}
}

function runPageBackground(){
  reportPageBackground(pageCanvasColor());
}

var pageBgLastAt = 0;
function schedPageBackground(slow){
  if (inFs() || pageBgTimer) return;
  // Khi cuộn (slow): nền trang hiếm khi đổi, nên giãn tối thiểu 700ms giữa hai lần lấy mẫu thay vì 180ms.
  var wait = slow === true ? Math.max(180, 700 - (Date.now() - pageBgLastAt)) : 180;
  pageBgTimer = setTimeout(function(){
    pageBgTimer = null;
    pageBgLastAt = Date.now();
    runPageBackground();
  }, wait);
}

function report(v){
  if (v === last) return;
  last = v;
  try {
    if (v === 'none') { EdgeBridge.clearTopColor(); }
    else {
      var p = v.split(',');
      EdgeBridge.setTopColor(Math.round(+p[0]), Math.round(+p[1]), Math.round(+p[2]));
    }
  } catch (e) {}
}

function runTop(){
  if (!document.elementsFromPoint) return;
  var w = window.innerWidth, color = null;
  try {
    var stack = document.elementsFromPoint(w * 0.5, 2);
    for (var j = 0; j < stack.length && !color; j++) {
      if (pinned(stack[j])) { color = climb(stack[j]); }
    }
    if (!color) {
      if ((window.scrollY || 0) > 2) { return; }
      color = stack.length ? climb(stack[0]) : null;
    }
    if (!color) { color = climb(document.body) || climb(document.documentElement); }
    report(color ? (color[0] + ',' + color[1] + ',' + color[2]) : 'none');
  } catch (e) {}
}

// Màu nền thật của header (nền có thể nằm ở phần tử con): lấy mẫu chỗ thấy được rồi mới leo lên cha
function headerColor(hdEl, I){
  try {
    var hb = window.__edgeHdBg;
    if (hb && hb.isConnected) { var c0 = climb(hb); if (c0) return c0; }
  } catch (e) {}
  try {
    var w = window.innerWidth, y = I + 4, xs = [0.03, 0.97, 0.5];
    for (var i = 0; i < xs.length; i++) {
      var st = document.elementsFromPoint(w * xs[i], y);
      for (var j = 0; j < st.length; j++) {
        if (!hdEl.contains(st[j])) continue;
        var c = climb(st[j]);
        if (c) return c;
      }
    }
    var kids = hdEl.querySelectorAll('*');
    for (var k = 0; k < kids.length && k < 60; k++) {
      var b = bgOf(kids[k]);
      if (b && kids[k].getBoundingClientRect().width > w * 0.6) return b;
    }
  } catch (e) {}
  var cl = climb(hdEl);
  if (cl) return cl;
  var dark = false;
  try { dark = window.matchMedia('(prefers-color-scheme: dark)').matches; } catch (e) {}
  return dark ? [24, 25, 26, 1] : [255, 255, 255, 1];
}

// Tìm khối header cố định (fixed/sticky) đang chồng lên dải thanh trạng thái, kể cả khi có lớp phủ rỗng
// (banner cookie...) nằm trên nó. Chọn khối ngoài cùng thấp hơn 30% màn hình và có chữ.
function findHeader(I){
  var w = window.innerWidth, h = window.innerHeight;
  var ys = [Math.max(2, I * 0.4), I + 4];
  var xs = [0.5, 0.15, 0.85], best = null;
  for (var q = 0; q < xs.length * ys.length; q++) {
    var st = document.elementsFromPoint(w * xs[q % xs.length], ys[Math.floor(q / xs.length)]);
    for (var j = 0; j < st.length; j++) {
      var el = st[j];
      while (el && el !== document.documentElement && el !== document.body) {
        var cs = getComputedStyle(el);
        if ((cs.position === 'fixed' || cs.position === 'sticky') && cs.display !== 'none' && parseFloat(cs.opacity) >= 0.1) {
          var r = el.getBoundingClientRect();
          // textContent của khối lớn rất nặng: chỉ lấy khi kích thước đã hợp lệ, và chỉ cần biết có chữ hay không.
          if (r.top <= I + 1 && r.width > w * 0.6 && r.height > 8 && r.height < h * 0.3 &&
              /\S/.test(el.textContent || '')) {
            if (!best || el.contains(best.el)) best = { el: el, cs: cs };
          }
        }
        el = el.parentElement;
      }
    }
  }
  return best;
}

// Các phần tử đã hạ xuống: YouTube có thể ghi đè lại style.top của chúng (vd. player về 48px), nên mỗi lần chạy
// kiểm tra lại giá trị thực tế và áp lại phần đã cộng thêm.
var edgeShiftList = [];
function edgeShiftTop(el, baseTop, delta){
  el.setAttribute('data-edge-shift', '1');
  el.__edgeDelta = delta;
  el.__edgeWant = baseTop + delta;
  el.style.setProperty('top', el.__edgeWant + 'px', 'important');
  edgeShiftList.push(el);
}
function edgeReassert(){
  try {
    for (var i = edgeShiftList.length - 1; i >= 0; i--) {
      var el = edgeShiftList[i];
      if (!el.isConnected) { edgeShiftList.splice(i, 1); continue; }
      var t = parseFloat(getComputedStyle(el).top);
      if (!isNaN(t) && Math.abs(t - el.__edgeWant) > 0.5) {
        el.__edgeWant = t + el.__edgeDelta;
        el.style.setProperty('top', el.__edgeWant + 'px', 'important');
      }
    }
  } catch (e) {}
}

// Các phần tử fixed/sticky nằm ngay dưới header (vd. player YouTube) cũng phải hạ theo header,
// nếu không header đã hạ xuống dưới thanh trạng thái sẽ đè lên chúng. Chỉ đụng tới phần tử bắt đầu
// trong dải header mới (từ đáy header cũ tới đáy header mới); lớp phủ bắt đầu từ mép trên thì bỏ qua.
function shiftCompanions(hd, I){
  try {
    var w = window.innerWidth;
    var hH = hd.el.getBoundingClientRect().height;
    var probeY = I + hH - 6;
    if (hH < 8 || probeY <= 0 || probeY >= window.innerHeight) return;
    var xs = [0.5, 0.2, 0.8];
    for (var q = 0; q < xs.length; q++) {
      var st = document.elementsFromPoint(w * xs[q], probeY);
      for (var j = 0; j < st.length; j++) {
        var el = st[j];
        while (el && el !== document.documentElement && el !== document.body) {
          if (el === hd.el || hd.el.contains(el) || el.contains(hd.el)) break;
          var cs = getComputedStyle(el);
          if ((cs.position === 'fixed' || cs.position === 'sticky') && cs.display !== 'none') {
            if (!el.hasAttribute('data-edge-shift')) {
              var r = el.getBoundingClientRect();
              var top = parseFloat(cs.top);
              if (!isNaN(top) && r.width > w * 0.6 && r.height > 20 && r.top >= hH - 4 && r.top < probeY) {
                edgeShiftTop(el, top, I);
              }
            }
            break;
          }
          el = el.parentElement;
        }
      }
    }
  } catch (e) {}
}

// Trình phát video nằm trong khối fixed/sticky (YouTube...) mà header đã hạ vừa đè lên: hạ khối đó xuống
// đúng bằng phần bị đè, bất kể giá trị top ban đầu là bao nhiêu.
function shiftVideoHost(hd, I){
  try {
    var vids = document.getElementsByTagName('video');
    var best = null, bestArea = 0;
    for (var i = 0; i < vids.length; i++) {
      var vr = vids[i].getBoundingClientRect();
      if (vr.width * vr.height > bestArea) { bestArea = vr.width * vr.height; best = vids[i]; }
    }
    if (!best || bestArea < 20000) return;
    var host = null;
    for (var el = best.parentElement; el && el !== document.documentElement && el !== document.body; el = el.parentElement) {
      var cs = getComputedStyle(el);
      if (cs.position === 'fixed' || cs.position === 'sticky') host = el;
    }
    if (!host || host === hd.el || hd.el.contains(host) || host.contains(hd.el)) return;
    if (host.hasAttribute('data-edge-shift')) return;
    var hs = getComputedStyle(host);
    var top = parseFloat(hs.top);
    var hb = hd.el.getBoundingClientRect().bottom;
    var rt = host.getBoundingClientRect().top;
    var delta = hb - rt;
    if (isNaN(top) || delta < 4 || rt < I - 4) return;
    edgeShiftTop(host, top, delta);
  } catch (e) {}
}

// Nền của header đôi khi là một khối fixed riêng (YouTube: mobile-topbar-header-background, z-index -1)
// nằm cùng vị trí và cao bằng header. Header đã hạ mà nền ở lại chỗ cũ thì chữ/icon trắng nằm trên nền trắng.
function shiftHeaderBackground(hd, I){
  try {
    var w = window.innerWidth;
    var hr = hd.el.getBoundingClientRect();
    var hH = hr.height, oldTop = hr.top - I;
    if (hH < 8) return;
    var cands = [], par = hd.el.parentElement, i, j;
    for (var lvl = 0; par && lvl < 2; lvl++, par = par.parentElement) {
      for (i = 0; i < par.children.length && i < 30; i++) cands.push(par.children[i]);
    }
    var st = document.elementsFromPoint(w * 0.5, Math.max(1, oldTop + hH - 4));
    for (j = 0; j < st.length; j++) cands.push(st[j]);
    for (i = 0; i < cands.length; i++) {
      var el = cands[i];
      if (el === hd.el || hd.el.contains(el) || el.contains(hd.el)) continue;
      if (el.hasAttribute('data-edge-shift') || el.hasAttribute('data-edge-hdbg')) continue;
      var cs = getComputedStyle(el);
      if ((cs.position !== 'fixed' && cs.position !== 'sticky') || cs.display === 'none') continue;
      var r = el.getBoundingClientRect(), top = parseFloat(cs.top);
      if (!isNaN(top) && r.width > w * 0.6 && Math.abs(r.top - oldTop) <= 2 && Math.abs(r.height - hH) <= 4) {
        el.setAttribute('data-edge-hdbg', '1');
        edgeShiftTop(el, top, I);
        window.__edgeHdBg = el;
      }
    }
  } catch (e) {}
}

// Header đã tìm thấy được giữ lại tối đa 1.5s: các lần chạy khi cuộn chỉ kiểm tra lại đúng phần tử đó (rẻ),
// còn việc dò từ đầu (nhiều elementsFromPoint) chỉ làm khi hết hạn, DOM đổi, đổi cỡ màn hình hoặc đổi tab.
var hdCache = null;
function cachedHeader(I){
  var c = hdCache;
  if (!c || !c.el.isConnected || Date.now() - c.at > 1500) return null;
  try {
    var cs = getComputedStyle(c.el);
    if ((cs.position !== 'fixed' && cs.position !== 'sticky') || cs.display === 'none' || parseFloat(cs.opacity) < 0.1) return null;
    var r = c.el.getBoundingClientRect(), w = window.innerWidth, h = window.innerHeight;
    if (r.top <= I + 1 && r.width > w * 0.6 && r.height > 8 && r.height < h * 0.3) return { el: c.el, cs: cs };
  } catch (e) {}
  return null;
}

function runEdge(){
  var I = window.__edgeInset || 0;
  if (!I || !document.elementsFromPoint) return;
  try {
    var hd = cachedHeader(I), fresh = false;
    if (!hd) {
      hd = findHeader(I);
      if (hd) { fresh = true; hdCache = { el: hd.el, at: Date.now() }; }
    }
    if (hd) {
      var topNow = parseFloat(hd.cs.top);
      if (isNaN(topNow) || topNow < I - 0.5) {
        hd.el.style.setProperty('top', I + 'px', 'important');
        hd.el.setAttribute('data-edge-hd', '1');
      }
      if (fresh && hd.el.getAttribute('data-edge-hd')) shiftHeaderBackground(hd, I);
      edgeReassert();
      if (fresh) { shiftCompanions(hd, I); shiftVideoHost(hd, I); }
      var hc = headerColor(hd.el, I);
      report(hc ? (hc[0] + ',' + hc[1] + ',' + hc[2]) : 'none');
      return;
    }
    var y = I + 2, w = window.innerWidth, hit = null, big = false, color = null;
    var xs = [0.5, 0.15, 0.85];
    for (var i = 0; i < xs.length && !hit; i++) {
      var stack = document.elementsFromPoint(w * xs[i], y);
      for (var j = 0; j < stack.length && !hit; j++) {
        var f = pinned(stack[j]);
        if (!f) continue;
        var r = f.getBoundingClientRect();
        if (r.top <= y && r.width > w * 0.6) {
          hit = f;
          big = r.height >= window.innerHeight * 0.45;
          color = climb(stack[j]);
        }
      }
    }
    if (!hit) { report('none'); return; }
    if (big) {
      if (!hit.getAttribute('data-edge-pad')) {
        hit.setAttribute('data-edge-pad', '1');
        var cs = styleCache.get(hit) || getComputedStyle(hit);
        if (!styleCache.has(hit)) styleCache.set(hit, cs);
        hit.style.setProperty('box-sizing', 'border-box', 'important');
        hit.style.setProperty('padding-top', ((parseFloat(cs.paddingTop) || 0) + I) + 'px', 'important');
      }
    } else {
      var cs2 = styleCache.get(hit) || getComputedStyle(hit);
      if (!styleCache.has(hit)) styleCache.set(hit, cs2);
      var top = parseFloat(cs2.top);
      if (!isNaN(top) && top < I - 0.5) {
        hit.style.setProperty('top', I + 'px', 'important');
      }
    }
    report(color ? (color[0] + ',' + color[1] + ',' + color[2]) : 'none');
  } catch (e) { report('none'); }
}

function run(){
  if (inFs()) return;
  if (window.__edgeMode) { runEdge(); }
  else { runTop(); }
}

// Không bỏ qua phần tử bị chính script ghi style (xem drainSelf) và không chạy dò header ở mọi khung hình khi cuộn:
// tối thiểu 80ms giữa hai lần chạy.
var observer = null;
var runLastAt = 0, runTimer = null;

// ---- Video toàn màn hình ----
// Trong lúc phát toàn màn hình, trình phát YouTube biến thành một khối cố định rất lớn và đổi kích thước liên tục:
// script tạm dừng hẳn (đỡ lag), và khi thoát thì xoá mọi thứ nó đã ghi vào trang (top đã dời, padding thêm cho khối lớn)
// rồi cho trang tự bố cục lại trước khi tính lại từ đầu.
function inFs(){ return !!(document.fullscreenElement || document.webkitFullscreenElement); }
var wasFs = false;
function edgeResetAll(){
  try {
    var els = document.querySelectorAll('[data-edge-shift],[data-edge-hd],[data-edge-hdbg],[data-edge-pad]');
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      if (el.hasAttribute('data-edge-pad')) {
        el.style.removeProperty('padding-top');
        el.style.removeProperty('box-sizing');
      }
      if (el.hasAttribute('data-edge-shift') || el.hasAttribute('data-edge-hd') || el.hasAttribute('data-edge-hdbg')) {
        el.style.removeProperty('top');
      }
      el.removeAttribute('data-edge-shift');
      el.removeAttribute('data-edge-hd');
      el.removeAttribute('data-edge-hdbg');
      el.removeAttribute('data-edge-pad');
    }
    edgeShiftList = [];
    hdCache = null;
    window.__edgeHdBg = null;
    styleCache = new WeakMap();
    last = '';
  } catch (e) {}
}
function fsCheck(){
  if (inFs()) { wasFs = true; return; }
  if (!wasFs) return;
  wasFs = false;
  edgeResetAll();
  try { window.dispatchEvent(new Event('resize')); } catch (e) {}   // để YouTube tự tính lại kích thước trình phát
  setTimeout(function(){ window.__edgeRun(); }, 250);
  setTimeout(function(){ window.__edgeRun(); }, 900);
}
document.addEventListener('fullscreenchange', fsCheck);
document.addEventListener('webkitfullscreenchange', fsCheck);
window.__edgeFsCheck = fsCheck;
function drainSelf(){
  // Mutation do chính script gây ra (dời top, translate...) đang nằm trong hàng đợi: bỏ đi để không tự kích hoạt lại
  try { if (observer) observer.takeRecords(); } catch (e) {}
}
function sched(){
  if (rafId || runTimer) return;
  var wait = Math.max(0, 80 - (Date.now() - runLastAt));
  if (wait > 0) { runTimer = setTimeout(function(){ runTimer = null; sched(); }, wait); return; }
  rafId = requestAnimationFrame(function(){
    rafId = null;
    runLastAt = Date.now();
    run();
    drainSelf();
  });
}


// ---- Nâng các thanh cố định ở đáy trang lên trên thanh địa chỉ nổi (giống Safari) ----
var lifted = [];
var compactBars = [];
var liftTimer = null, liftLast = 0;

function fixedOf(el){
  while (el && el !== document.documentElement && el !== document.body) {
    try {
      var s = getComputedStyle(el);
      if (s.position === 'fixed' && s.display !== 'none' && parseFloat(s.opacity) >= 0.1) return el;
    } catch (e) { return null; }
    el = el.parentElement;
  }
  return null;
}

function findRec(el){
  for (var i = 0; i < lifted.length; i++) { if (lifted[i].el === el) return lifted[i]; }
  return null;
}

// Chỉ nhận diện thanh điều hướng cố định dạng dải ngang ở đáy trang.
// Không coi các nút nổi độc lập (gọi điện, chat, Zalo...) là toolbar.
function compactBarRec(el){
  for (var i = 0; i < compactBars.length; i++) { if (compactBars[i].el === el) return compactBars[i]; }
  return null;
}

function saveInline(el, name){
  return { value: el.style.getPropertyValue(name), priority: el.style.getPropertyPriority(name) };
}

function restoreInline(el, name, saved){
  if (saved.value) el.style.setProperty(name, saved.value, saved.priority);
  else el.style.removeProperty(name);
}

function isBottomNav(el, H, W, offset, known){
  try {
    if (!el || !el.isConnected || el === document.body || el === document.documentElement) return false;
    var cs = getComputedStyle(el);
    if (cs.position !== 'fixed' || cs.display === 'none') return false;
    if (!known && (cs.visibility === 'hidden' || parseFloat(cs.opacity) < 0.1)) return false;
    var r = el.getBoundingClientRect();
    // Sau khi morph, getBoundingClientRect() co lại theo pill. Dùng kích thước layout
    // cho record đã nhận diện để không nhầm animation đang chạy là một toolbar khác.
    var checkW = known ? (el.offsetWidth || r.width) : r.width;
    var checkH = known ? (el.offsetHeight || r.height) : r.height;
    if (checkW < W * 0.78 || checkH < 28 || checkH > H * 0.18) return false;
    if (!known) {
      var top = r.top + (offset || 0), bottom = r.bottom + (offset || 0);
      if (top < H * 0.55 || bottom < H - 240) return false;
    }
    var nodes = el.querySelectorAll('a,button,[role="button"],[onclick]');
    var controls = 0;
    for (var i = 0; i < nodes.length; i++) {
      var n = nodes[i], ns = getComputedStyle(n), nr = n.getBoundingClientRect();
      if (ns.display !== 'none' && (n.offsetWidth || nr.width) > 2 && (n.offsetHeight || nr.height) > 2) controls++;
      if (controls >= 3) return true;
    }
  } catch (e) {}
  return false;
}

// Tìm cả ancestor fixed rộng, phòng trường hợp từng nút con tự có position:fixed.
// Nhớ kết quả từng phần tử: trong cùng một lượt dò, nhiều điểm lấy mẫu cùng đi qua một chuỗi phần tử cha
// (body > #app > main...) nên không cần xét lại; phần tử vừa bị loại thì bỏ qua 700ms (không đếm lại nút bên trong).
var navMemo = new WeakMap(), navRun = 0;
function wideBottomNavOf(el, H, W){
  var now = Date.now();
  for (; el && el !== document.documentElement && el !== document.body; el = el.parentElement) {
    var m = navMemo.get(el);
    if (m && m.run === navRun) { if (m.ok) return el; continue; }
    if (m && !m.ok && now - m.t < 700) continue;
    var rec = compactBarRec(el);
    var liftedRec = findRec(el);
    var offset = liftedRec ? liftedRec.cur : 0;
    var ok = isBottomNav(el, H, W, offset, !!rec);
    navMemo.set(el, { t: now, run: navRun, ok: ok });
    if (ok) return el;
  }
  return null;
}

function registerCompactBar(el){
  if (compactBarRec(el)) return;
  try {
    var originalBorderRadius = saveInline(el, 'border-radius');
    var computedRadius = parseFloat(getComputedStyle(el).borderTopLeftRadius) || 0;
    // Giữ nguyên hình chữ nhật của website, chỉ bo nhẹ bốn góc; không morph thành pill.
    var roundedRadius = Math.max(16, computedRadius);
    el.style.setProperty('border-radius', roundedRadius + 'px', 'important');
    compactBars.push({ el: el, hidden: false, cycleStarted: false, animationFrame: null,
      savedOpacity: null, savedVisibility: null, savedPointerEvents: null,
      savedTransform: null, savedTransformOrigin: null, savedWillChange: null,
      savedBorderRadius: null, originalBorderRadius: originalBorderRadius, savedOverflow: null,
      baseTransform: 'none', baseOpacity: 1, baseVisualWidth: 0, baseVisualHeight: 0, baseRadius: roundedRadius,
      visualState: null, velocity: null, targetState: null });
  } catch (e) {}
}

function captureCompactBase(rec){
  if (rec.cycleStarted) return;
  var el = rec.el, cs = getComputedStyle(el);
  rec.savedOpacity = saveInline(el, 'opacity');
  rec.savedVisibility = saveInline(el, 'visibility');
  rec.savedPointerEvents = saveInline(el, 'pointer-events');
  rec.savedTransform = saveInline(el, 'transform');
  rec.savedTransformOrigin = saveInline(el, 'transform-origin');
  rec.savedWillChange = saveInline(el, 'will-change');
  rec.savedBorderRadius = saveInline(el, 'border-radius');
  rec.savedOverflow = saveInline(el, 'overflow');
  rec.baseRadius = parseFloat(cs.borderTopLeftRadius) || 0;
  rec.baseTransform = cs.transform || 'none';
  var baseRect = el.getBoundingClientRect();
  rec.baseVisualWidth = baseRect.width || el.offsetWidth || 1;
  rec.baseVisualHeight = baseRect.height || el.offsetHeight || 1;
  rec.baseOpacity = parseFloat(cs.opacity);
  if (!isFinite(rec.baseOpacity)) rec.baseOpacity = 1;
  rec.visualState = { x: 0, y: 0, sx: 1, sy: 1, opacity: rec.baseOpacity, radius: rec.baseRadius };
  rec.velocity = { x: 0, y: 0, sx: 0, sy: 0, opacity: 0, radius: 0 };
  rec.cycleStarted = true;
}

function restoreCompactBase(rec, keepHidden){
  var el = rec.el;
  if (!el || !rec.cycleStarted) return;
  try {
    if (keepHidden) {
      // Giữ transform đã co về pill để lần gọi tiếp theo spring bung ra từ đúng vị trí này.
      el.style.setProperty('visibility', 'hidden', 'important');
      el.style.setProperty('pointer-events', 'none', 'important');
      restoreInline(el, 'will-change', rec.savedWillChange);
    } else {
      restoreInline(el, 'transform', rec.savedTransform);
      restoreInline(el, 'transform-origin', rec.savedTransformOrigin);
      restoreInline(el, 'opacity', rec.savedOpacity);
      restoreInline(el, 'will-change', rec.savedWillChange);
      restoreInline(el, 'border-radius', rec.savedBorderRadius);
      el.style.setProperty('border-radius', rec.baseRadius + 'px', 'important');
      restoreInline(el, 'overflow', rec.savedOverflow);
      restoreInline(el, 'visibility', rec.savedVisibility);
      restoreInline(el, 'pointer-events', rec.savedPointerEvents);
      rec.cycleStarted = false;
      rec.visualState = { x: 0, y: 0, sx: 1, sy: 1, opacity: rec.baseOpacity, radius: rec.baseRadius };
      rec.velocity = { x: 0, y: 0, sx: 0, sy: 0, opacity: 0, radius: 0 };
      rec.targetState = null;
    }
  } catch (e) {}
}

function applyCompactState(rec){
  var el = rec.el, s = rec.visualState;
  var base = rec.baseTransform && rec.baseTransform !== 'none' ? ' ' + rec.baseTransform : '';
  el.style.setProperty('transform-origin', '50% 50%', 'important');
  el.style.setProperty('will-change', 'transform, opacity', 'important');
  el.style.setProperty('transform', 'translate3d(' + s.x + 'px,' + s.y + 'px,0) scale(' + s.sx + ',' + s.sy + ')' + base, 'important');
  el.style.setProperty('opacity', String(Math.max(0, Math.min(1, s.opacity))), 'important');
  el.style.setProperty('border-radius', Math.max(0, s.radius) + 'px', 'important');
  el.style.setProperty('overflow', 'hidden', 'important');
}

function compactTarget(rec){
  var el = rec.el, r = el.getBoundingClientRect();
  var liftRec = findRec(el), currentLift = liftRec ? liftRec.cur : 0;
  // runLift sẽ cập nhật translate từ currentLift sang __edgeBottom ngay sau hàm này.
  var liftDelta = currentLift - (window.__edgeBottom || 0);
  var cx = (r.left + r.right) / 2, cy = (r.top + r.bottom) / 2 + liftDelta;
  var px = isFinite(window.__edgePillX) ? window.__edgePillX : (window.innerWidth || 0) / 2;
  var py = isFinite(window.__edgePillY) ? window.__edgePillY : (window.innerHeight || 0) - 34;
  var pw = isFinite(window.__edgePillW) && window.__edgePillW > 0 ? window.__edgePillW : (window.innerWidth || 0) * 0.40;
  var ph = isFinite(window.__edgePillH) && window.__edgePillH > 0 ? window.__edgePillH : 25;
  var dx = px - cx, dy = py - cy;
  // Kích thước gốc của chu kỳ tránh sai số khi người dùng đảo hướng giữa lúc đang morph.
  var baseW = rec.baseVisualWidth || el.offsetWidth || r.width;
  var baseH = rec.baseVisualHeight || el.offsetHeight || r.height;
  var sx = Math.max(0.06, Math.min(0.96, pw / Math.max(1, baseW)));
  var sy = Math.max(0.12, Math.min(0.96, ph / Math.max(1, baseH)));
  var current = rec.visualState || { x: 0, y: 0 };
  return { x: current.x + dx, y: current.y + dy, sx: sx, sy: sy, opacity: 0.02, radius: rec.baseRadius };
}

function finishCompactAnimation(rec, hide){
  if (!rec.el || !rec.el.isConnected) return;
  if (hide && rec.hidden) {
    rec.visualState = rec.targetState;
    applyCompactState(rec);
    restoreCompactBase(rec, true);
  } else if (!hide && !rec.hidden) {
    restoreCompactBase(rec, false);
  }
}

// Spring vật lý dùng cùng tinh thần SETTLE/DISMISS của AnimationSystem bên Android.
// Đảo hướng cuộn giữa chừng vẫn giữ nguyên vị trí và vận tốc nên không bị giật về đầu.
function springCompactTo(rec, target, hide){
  // Thu vào và bung ra đều gần nhịp SETTLE (thu vào nhanh hơn một chút, bung ra nảy rõ hơn).
  var response = hide ? 0.50 : 0.46;
  var damping = hide ? 0.62 : 0.72;
  var omega = 2 * Math.PI / response;
  var omega2 = omega * omega;
  var twiceDampingOmega = 2 * damping * omega;
  var keys = ['x', 'y', 'sx', 'sy', 'opacity', 'radius'];
  rec.targetState = target;
  var lastTime = 0;

  function tick(now){
    if (!rec.el || !rec.el.isConnected) {
      rec.animationFrame = null;
      return;
    }
    if (!lastTime) lastTime = now;
    var dt = Math.max(0.001, Math.min(0.032, (now - lastTime) / 1000));
    lastTime = now;
    var settled = true;
    for (var i = 0; i < keys.length; i++) {
      var k = keys[i], value = rec.visualState[k], velocity = rec.velocity[k];
      var error = target[k] - value;
      var acceleration = omega2 * error - twiceDampingOmega * velocity;
      velocity += acceleration * dt;
      value += velocity * dt;
      rec.visualState[k] = value;
      rec.velocity[k] = velocity;
      var valueEpsilon = (k === 'x' || k === 'y') ? 0.35 : (k === 'opacity' ? 0.006 : 0.0015);
      var velocityEpsilon = (k === 'x' || k === 'y') ? 5 : (k === 'opacity' ? 0.025 : 0.012);
      if (Math.abs(target[k] - value) > valueEpsilon || Math.abs(velocity) > velocityEpsilon) settled = false;
    }
    applyCompactState(rec);
    if (settled) {
      rec.visualState = { x: target.x, y: target.y, sx: target.sx, sy: target.sy, opacity: target.opacity, radius: target.radius };
      rec.velocity = { x: 0, y: 0, sx: 0, sy: 0, opacity: 0, radius: 0 };
      applyCompactState(rec);
      rec.animationFrame = null;
      finishCompactAnimation(rec, hide);
    } else {
      rec.animationFrame = requestAnimationFrame(tick);
    }
  }

  if (rec.animationFrame) cancelAnimationFrame(rec.animationFrame);
  rec.animationFrame = requestAnimationFrame(tick);
}

function setCompactBar(rec, hide){
  if (!rec || !rec.el || !rec.el.isConnected || hide === rec.hidden) return;
  var el = rec.el;
  captureCompactBase(rec);
  rec.hidden = hide;
  el.style.setProperty('transform-origin', '50% 50%', 'important');
  el.style.setProperty('will-change', 'transform, opacity', 'important');
  el.style.setProperty('pointer-events', 'none', 'important');

  if (hide) {
    var target = compactTarget(rec);
    springCompactTo(rec, target, true);
  } else {
    // Bật visibility khi thanh đang ở đúng vị trí pill; spring bung ra về kích thước ban đầu.
    el.style.setProperty('visibility', 'visible', 'important');
    springCompactTo(rec, {
      x: 0, y: 0, sx: 1, sy: 1, opacity: rec.baseOpacity, radius: rec.baseRadius
    }, false);
  }
}

function releaseCompactBar(rec){
  if (!rec || !rec.el) return;
  try {
    if (rec.animationFrame) { cancelAnimationFrame(rec.animationFrame); rec.animationFrame = null; }
    rec.hidden = false;
    if (rec.cycleStarted) restoreCompactBase(rec, false);
    if (rec.originalBorderRadius) restoreInline(rec.el, 'border-radius', rec.originalBorderRadius);
  } catch (e) {}
}

function syncCompactBars(){
  var keep = [], H = window.innerHeight, W = window.innerWidth;
  for (var i = 0; i < compactBars.length; i++) {
    var rec = compactBars[i], offsetRec = findRec(rec.el), offset = offsetRec ? offsetRec.cur : 0;
    if (isBottomNav(rec.el, H, W, offset, true)) {
      setCompactBar(rec, !!window.__edgeCompact);
      keep.push(rec);
    } else {
      releaseCompactBar(rec);
    }
  }
  compactBars = keep;
}

function liftEl(el){
  var cs = getComputedStyle(el);
  var base = '';
  var durs = (cs.transitionDuration || '0s').split(',');
  for (var i = 0; i < durs.length; i++) { if (parseFloat(durs[i]) > 0) { base = cs.transition + ', '; break; } }
  lifted.push({
    el: el,
    cur: 0,
    savedTranslate: el.style.getPropertyValue('translate'),
    savedTransition: el.style.getPropertyValue('transition')
  });
  el.style.setProperty('transition', base + 'translate 250ms cubic-bezier(0.2, 0.8, 0.2, 1)', 'important');
}

function release(rec){
  var el = rec.el;
  try {
    if (rec.savedTranslate) el.style.setProperty('translate', rec.savedTranslate);
    else el.style.removeProperty('translate');
    if (rec.savedTransition) el.style.setProperty('transition', rec.savedTransition);
    else el.style.removeProperty('transition');
  } catch (e) {}
}

function applyLift(){
  var R = window.__edgeBottom || 0;
  var want = R > 0 ? ('0px -' + R + 'px') : '';
  for (var i = 0; i < lifted.length; i++) {
    var rec = lifted[i];
    rec.cur = R;
    var now = rec.el.style.getPropertyValue('translate');
    if (R > 0) {
      if (now !== want) rec.el.style.setProperty('translate', want, 'important');
    } else if (rec.savedTranslate) {
      if (now !== rec.savedTranslate) rec.el.style.setProperty('translate', rec.savedTranslate);
    } else if (now) {
      rec.el.style.removeProperty('translate');
    }
  }
}

function runLift(){
  if (inFs()) return;
  var R = window.__edgeBottom || 0;
  var H = window.innerHeight, W = window.innerWidth;
  var zone = 200;
  var keep = [];
  for (var i = 0; i < lifted.length; i++) {
    var rec = lifted[i], ok = false;
    try {
      if (rec.el.isConnected) {
        var r0 = rec.el.getBoundingClientRect();
        var s0 = getComputedStyle(rec.el);
        if (r0.width > 1 && r0.height > 1 && s0.position === 'fixed' && s0.display !== 'none' &&
            r0.bottom + rec.cur >= H - zone && r0.height < H * 0.45) ok = true;
      }
    } catch (e) {}
    if (ok) keep.push(rec); else release(rec);
  }
  lifted = keep;
  syncCompactBars();
  if (R > 0 && document.elementsFromPoint) {
    navRun++;
    var ys = [H - 4, H - 60, H - 140];
    var xs = [0.1, 0.5, 0.9];
    for (var a = 0; a < ys.length; a++) {
      for (var b = 0; b < xs.length; b++) {
        var stack = document.elementsFromPoint(W * xs[b], ys[a]);
        for (var c = 0; c < stack.length; c++) {
          var nav = wideBottomNavOf(stack[c], H, W);
          if (nav) registerCompactBar(nav);
          var f = fixedOf(stack[c]);
          if (!f) continue;
          if (findRec(f)) break;
          var covered = false;
          for (var k = 0; k < lifted.length; k++) { if (lifted[k].el.contains(f)) { covered = true; break; } }
          if (covered) break;
          var r = f.getBoundingClientRect();
          if (r.height >= H * 0.45 || r.width < 1 || r.top < H * 0.4) break;
          liftEl(f);
          break;
        }
      }
    }
  }
  syncCompactBars();
  applyLift();
  drainSelf();
}

function schedLift(){
  if (liftTimer) return;
  var wait = Math.max(0, 250 - (Date.now() - liftLast));
  liftTimer = setTimeout(function(){ liftTimer = null; liftLast = Date.now(); runLift(); }, wait);
}

function schedAll(){ hdCache = null; sched(); schedLift(); schedPageBackground(); }

window.__edgeRun = function(){ hdCache = null; run(); runLift(); schedPageBackground(); };
window.__edgePageBgRun = function(){ runPageBackground(); };
// Đổi tab: native đã bỏ qua báo cáo màu lúc trang này chạy nền, nên xoá bộ nhớ 'last' để báo lại.
window.__edgeForceTop = function(){ last = ''; hdCache = null; run(); };
window.__edgeLift = function(){ runLift(); };
window.addEventListener('scroll', function(){
  sched();
  schedPageBackground(true);
}, {passive: true});

// Kéo-để-tải-lại của app chỉ nên chạy khi cử chỉ thuộc về trang cuộn gốc: báo về nếu chạm bắt đầu trong khung cuộn
// đã cuộn xuống, trên trình phát video, hoặc nếu trang tự preventDefault khi kéo.
(function(){
  var guard = -1;
  function report(b){
    if (guard === b) return;
    guard = b;
    try { EdgeBridge.pullGuard(b); } catch (e) {}
  }
  function innerScrolled(el){
    var n = 0;
    while (el && el.nodeType === 1 && n++ < 40) {
      if (el !== document.body && el !== document.documentElement && el.scrollTop > 0) {
        var oy = '';
        try { oy = getComputedStyle(el).overflowY; } catch (e) {}
        if (oy === 'auto' || oy === 'scroll' || oy === 'overlay') return true;
      }
      el = el.parentElement;
    }
    return false;
  }
  function onVideo(t){
    try {
      if (t.closest && t.closest('video, .html5-video-player, #player, ytm-player')) return true;
      var st = document.elementsFromPoint ? document.elementsFromPoint(t.__x, t.__y) : [];
      for (var i = 0; i < st.length; i++) { if (st[i].tagName === 'VIDEO') return true; }
    } catch (e) {}
    return false;
  }
  window.addEventListener('touchstart', function(e){
    guard = -1;
    var t = e.target, tc = e.touches && e.touches[0];
    if (!t || !tc || e.touches.length > 1) { report(1); return; }
    var probe = { closest: function(s){ return t.closest ? t.closest(s) : null; }, __x: tc.clientX, __y: tc.clientY };
    report(innerScrolled(t) || onVideo(probe) ? 1 : 0);
  }, {capture: true, passive: true});
  window.addEventListener('touchmove', function(e){
    if (e.defaultPrevented) report(1);
  }, {passive: true});
})();
window.addEventListener('resize', schedAll);
window.addEventListener('load', schedPageBackground, true);
window.addEventListener('pageshow', schedPageBackground);

try {
  var colorSchemeQuery = window.matchMedia('(prefers-color-scheme: dark)');
  if (colorSchemeQuery.addEventListener) colorSchemeQuery.addEventListener('change', schedPageBackground);
  else if (colorSchemeQuery.addListener) colorSchemeQuery.addListener(schedPageBackground);
} catch (e) {}

try {
  var obsLast = 0, obsTimer = null;
  observer = new MutationObserver(function(records){
    if (inFs()) return;
    for (var i = 0; i < records.length; i++) {
      if (records[i].type === 'childList') { hdCache = null; schedAll(); return; }   // thêm/bớt phần tử: làm đủ như cũ
    }
    // Đổi class/style ngay trên html/body (bật tắt chế độ tối...) mới làm nền trang đổi: báo lại màu ngay,
    // vì lúc cuộn màu chỉ được lấy mẫu thưa.
    for (var j = 0; j < records.length; j++) {
      var tg = records[j].target;
      if (tg === document.body || tg === document.documentElement) { schedPageBackground(); break; }
    }
    // Bỏ qua các lần đổi style do chính script ghi lên thanh dưới (animation thu/bung ghi style mỗi khung hình):
    // nếu không, animation tự kích hoạt dò lại cả trang. Chỉ khi còn thay đổi của trang thì mới xếp lịch.
    var foreign = false;
    for (var k = 0; k < records.length && !foreign; k++) {
      var tt = records[k].target;
      if (!compactBarRec(tt) && !findRec(tt)) foreign = true;
    }
    if (!foreign) return;
    // Chỉ đổi class/style (hoạt ảnh, carousel...): nhẹ hơn và gộp tối đa một lần mỗi 150ms
    if (obsTimer) return;
    var wait = Math.max(0, 150 - (Date.now() - obsLast));
    obsTimer = setTimeout(function(){ obsTimer = null; obsLast = Date.now(); sched(); schedLift(); }, wait);
  });
  observer.observe(document.documentElement,
    {childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'style']});
  window.addEventListener('beforeunload', function(){
    observer.disconnect();
    if (pageBgTimer) clearTimeout(pageBgTimer);
    pageBgTimer = null;
    styleCache = null;
  });
} catch (e) {}

// Đo thời gian chạy (tắt mặc định, gần như không tốn gì khi tắt). Bật bằng window.__edgePerf = true,
// đọc bằng window.__edgePerfReport(): số lần chạy, tổng ms, ms lớn nhất của run / runLift / pageBg.
var perf = {};
function wrapPerf(name, fn){
  return function(){
    if (!window.__edgePerf) return fn.apply(this, arguments);
    var t0 = performance.now();
    try { return fn.apply(this, arguments); }
    finally {
      var d = performance.now() - t0, s = perf[name] || (perf[name] = { n: 0, total: 0, max: 0 });
      s.n++; s.total += d; if (d > s.max) s.max = d;
    }
  };
}
run = wrapPerf('run', run);
runLift = wrapPerf('runLift', runLift);
runPageBackground = wrapPerf('pageBg', runPageBackground);
window.__edgePerfReport = function(){
  var out = {};
  Object.keys(perf).forEach(function(k){
    var s = perf[k];
    out[k] = { n: s.n, avgMs: +(s.total / s.n).toFixed(2), maxMs: +s.max.toFixed(2), totalMs: +s.total.toFixed(1) };
  });
  return JSON.stringify(out);
};

setTimeout(window.__edgeRun, 100);
setTimeout(window.__edgeRun, 800);
setTimeout(window.__edgeRun, 2000);
window.__edgeRun();
})();
