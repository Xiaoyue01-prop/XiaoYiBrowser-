package com.example.webedge


// Script chạy trong trang. Chế độ tràn viền: đẩy thanh menu cố định ở đầu trang xuống dưới thanh trạng thái
// (hoặc chừa chỗ cho lớp phủ toàn màn hình) và báo màu thanh đó về app để tô dải thanh trạng thái.
// Chế độ thường: chỉ đọc màu mép trên của trang.
internal const val EDGE_JS = """
(function(){
window.__edgeInset = %INSET%;
window.__edgeMode = %MODE%;
window.__edgeBottom = %BOTTOM%;
if (window.__edgeStarted) { if (window.__edgeRun) window.__edgeRun(); return; }
window.__edgeStarted = true;

var last = '';
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
          var txt = (el.textContent || '').trim().length;
          if (r.top <= I + 1 && r.width > w * 0.6 && r.height > 8 && r.height < h * 0.3 && txt > 0) {
            if (!best || el.contains(best.el)) best = { el: el, cs: cs };
          }
        }
        el = el.parentElement;
      }
    }
  }
  return best;
}

function runEdge(){
  var I = window.__edgeInset || 0;
  if (!I || !document.elementsFromPoint) return;
  try {
    var hd = findHeader(I);
    if (hd) {
      var topNow = parseFloat(hd.cs.top);
      if (isNaN(topNow) || topNow < I - 0.5) hd.el.style.setProperty('top', I + 'px', 'important');
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
  if (window.__edgeMode) { runEdge(); }
  else { runTop(); }
}

function sched(){
  if (rafId) return;
  rafId = requestAnimationFrame(function(){
    rafId = null;
    run();
  });
}


// ---- Nâng các thanh cố định ở đáy trang lên trên thanh địa chỉ nổi (giống Safari) ----
var lifted = [];
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
  if (R > 0 && document.elementsFromPoint) {
    var ys = [H - 4, H - 30, H - 70, H - 120, H - 170];
    var xs = [0.08, 0.3, 0.5, 0.7, 0.92];
    for (var a = 0; a < ys.length; a++) {
      for (var b = 0; b < xs.length; b++) {
        var stack = document.elementsFromPoint(W * xs[b], ys[a]);
        for (var c = 0; c < stack.length; c++) {
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
  applyLift();
}

function schedLift(){
  if (liftTimer) return;
  var wait = Math.max(0, 250 - (Date.now() - liftLast));
  liftTimer = setTimeout(function(){ liftTimer = null; liftLast = Date.now(); runLift(); }, wait);
}

function schedAll(){ sched(); schedLift(); }

window.__edgeRun = function(){ run(); runLift(); };
window.__edgeLift = function(){ runLift(); };
window.addEventListener('scroll', sched, {passive: true});
window.addEventListener('resize', schedAll);

try {
  var observer = new MutationObserver(schedAll);
  observer.observe(document.documentElement,
    {childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'style']});
  window.addEventListener('beforeunload', function(){
    observer.disconnect();
    styleCache = null;
  });
} catch (e) {}

setTimeout(window.__edgeRun, 100);
setTimeout(window.__edgeRun, 800);
setTimeout(window.__edgeRun, 2000);
window.__edgeRun();
})();
"""
