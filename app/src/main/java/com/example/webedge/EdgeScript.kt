package com.example.webedge

internal const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

// Script chạy trong trang. Chế độ tràn viền: đẩy thanh menu cố định ở đầu trang xuống dưới thanh trạng thái
// (hoặc chừa chỗ cho lớp phủ toàn màn hình) và báo màu thanh đó về app để tô dải thanh trạng thái.
// Chế độ thường: chỉ đọc màu mép trên của trang.
internal const val EDGE_JS = """
(function(){
window.__edgeInset = %INSET%;
window.__edgeMode = %MODE%;
if (window.__edgeStarted) { if (window.__edgeRun) window.__edgeRun(); return; }
window.__edgeStarted = true;

var last = '';
var styleCache = new WeakMap();
var rafId = null;

function rgbOf(c){
  try {
    var m = c && c.match(/rgba?\(([^)]+)\)/);
    if (!m) return null;
    var p = m[1].split(',').map(function(x){ return parseFloat(x); });
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

function runEdge(){
  var I = window.__edgeInset || 0;
  if (!I || !document.elementsFromPoint) return;
  try {
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

window.__edgeRun = run;
window.addEventListener('scroll', sched, {passive: true});
window.addEventListener('resize', sched);

try {
  var observer = new MutationObserver(sched);
  observer.observe(document.documentElement,
    {childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'style']});
  window.addEventListener('beforeunload', function(){
    observer.disconnect();
    styleCache = null;
  });
} catch (e) {}

setTimeout(run, 100);
setTimeout(run, 800);
setTimeout(run, 2000);
run();
})();
"""
