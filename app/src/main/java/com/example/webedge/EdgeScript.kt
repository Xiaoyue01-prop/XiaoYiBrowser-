package com.example.webedge


// Script chạy trong trang. Chế độ tràn viền: đẩy thanh menu cố định ở đầu trang xuống dưới thanh trạng thái
// (hoặc chừa chỗ cho lớp phủ toàn màn hình) và báo màu thanh đó về app để tô dải thanh trạng thái.
// Chế độ thường: chỉ đọc màu mép trên của trang.
internal const val EDGE_JS = """
(function(){
window.__edgeInset = %INSET%;
window.__edgeMode = %MODE%;
window.__edgeBottom = %BOTTOM%;
window.__edgeCompact = %COMPACT%;
%PILL_METRICS%
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
function wideBottomNavOf(el, H, W){
  for (; el && el !== document.documentElement && el !== document.body; el = el.parentElement) {
    var rec = compactBarRec(el);
    var liftedRec = findRec(el);
    var offset = liftedRec ? liftedRec.cur : 0;
    if (isBottomNav(el, H, W, offset, !!rec)) return el;
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

// Spring vật lý dùng cùng tinh thần MORPH/MORPH_CLOSE của AnimationSystem bên Android.
// Đảo hướng cuộn giữa chừng vẫn giữ nguyên vị trí và vận tốc nên không bị giật về đầu.
function springCompactTo(rec, target, hide){
  // Thu vào gần cùng nhịp SMOOTH của pill; bung ra dùng MORPH để có độ nảy rõ hơn.
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
    var ys = [H - 4, H - 30, H - 70, H - 120, H - 170];
    var xs = [0.08, 0.3, 0.5, 0.7, 0.92];
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
}

function schedLift(){
  if (liftTimer) return;
  var wait = Math.max(0, 250 - (Date.now() - liftLast));
  liftTimer = setTimeout(function(){ liftTimer = null; liftLast = Date.now(); runLift(); }, wait);
}

function schedAll(){ sched(); schedLift(); }

window.__edgeRun = function(){ run(); runLift(); };
// Đổi tab: native đã bỏ qua báo cáo màu lúc trang này chạy nền, nên xoá bộ nhớ 'last' để báo lại.
window.__edgeForceTop = function(){ last = ''; run(); };
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
