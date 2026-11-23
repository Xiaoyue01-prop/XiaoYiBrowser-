(function(){
window.__edgeInset = %INSET%;
window.__edgeMode = %MODE%;
window.__edgeBottom = %BOTTOM%;
window.__edgeScrollBottom = %SCROLL_BOTTOM%;
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

function viewportScale(){
  return window.visualViewport && window.visualViewport.scale || 1;
}

function topReserveCss(){
  return (window.__edgeInset || 0) / viewportScale();
}

function viewportZoomed(){
  var vp = window.visualViewport;
  return vp && vp.width < document.documentElement.clientWidth * 0.94;
}

var rootReserveStyle = null, rootReserveDirty = true, rootReserveKey = '';
function syncRootReserve(){
  var root = document.documentElement;
  if (!root || viewportZoomed()) return;
  var scale = viewportScale();
  var top = window.__edgeMode ? topReserveCss() : 0;
  var bottom = (window.__edgeScrollBottom || 0) / scale;
  var key = top + ',' + bottom;
  if (!rootReserveDirty && rootReserveKey === key) return;
  rootReserveDirty = false;
  rootReserveKey = key;

  // Đo padding của trang khi quy tắc của Summit đã tắt để tránh cộng hai lần safe area.
  if (rootReserveStyle) rootReserveStyle.disabled = true;
  var rootStyle = getComputedStyle(root);
  var bodyStyle = document.body && getComputedStyle(document.body);
  var bodyInFlow = bodyStyle && /^(static|relative|sticky)$/.test(bodyStyle.position) &&
    !/^(auto|scroll|hidden)$/.test(bodyStyle.overflowY);
  var bodyTop = bodyInFlow ? parseFloat(bodyStyle.paddingTop) || 0 : 0;
  var bodyBottom = bodyInFlow ? parseFloat(bodyStyle.paddingBottom) || 0 : 0;
  var rule = '';
  if (top - bodyTop > parseFloat(rootStyle.paddingTop) + 0.5) {
    rule += 'padding-top:' + (top - bodyTop) + 'px!important;';
  }
  if (bottom - bodyBottom > parseFloat(rootStyle.paddingBottom) + 0.5) {
    rule += 'padding-bottom:' + (bottom - bodyBottom) + 'px!important;';
  }
  if (!rule) return;
  if (!rootReserveStyle || !rootReserveStyle.isConnected) {
    rootReserveStyle = document.createElement('style');
    (document.head || root).appendChild(rootReserveStyle);
  }
  rule = 'html{' + rule + '}';
  if (rootReserveStyle.textContent !== rule) rootReserveStyle.textContent = rule;
  rootReserveStyle.disabled = false;
}

var cvCtx = null;
// Màu hiện đại (color(srgb ...), oklab, hsl...) không khớp rgb() -> nhờ canvas đổi sang RGB
function viaCanvas(c, translucent){
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
    if (!d[3] || (!translucent && d[3] / 255 < 0.85)) return null;
    return [d[0], d[1], d[2], d[3] / 255];
  } catch (e) { return null; }
}

function rgbOf(c, translucent){
  try {
    if (!c || c === 'transparent') return null;
    var m = c.match(/^rgba?\(([^)]+)\)/);
    if (!m) return viaCanvas(c, translucent);
    var p = m[1].split(',').map(function(x){ return parseFloat(x); });
    if (p.length < 3 || isNaN(p[0])) return viaCanvas(c, translucent);
    var a = p.length > 3 ? p[3] : 1;
    if (a <= 0 || (!translucent && a < 0.85)) return null;
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

function headerPaint(el){
  var cs = getComputedStyle(el);
  var color = gradientBackgroundColor(cs) || rgbOf(cs.backgroundColor, true);
  if (!color) return null;
  return [color[0], color[1], color[2], (color.length > 3 ? color[3] : 1) * parseFloat(cs.opacity)];
}

function paintOver(front, back){
  if (!front || !back) return front || back;
  var underneath = back[3] * (1 - front[3]);
  var alpha = front[3] + underneath;
  if (!alpha) return null;
  return front.slice(0, 3).map(function(value, channel){
    return (value * front[3] + back[channel] * underneath) / alpha;
  }).concat(alpha);
}

// Màu nền của thanh trong suốt lấy cả lớp nền trang ở phía sau điểm lấy mẫu.
function headerColor(hdEl, useHeaderBackground){
  var hb = useHeaderBackground === false ? null : window.__edgeHdBg;
  var separate = hb && hb.isConnected && headerStyleVisible(getComputedStyle(hb)) ? headerPaint(hb) : null;
  var canvas = bgOf(document.body) || bgOf(document.documentElement);
  var dark = window.matchMedia('(prefers-color-scheme: dark)').matches;
  var base = canvas ? canvas.slice(0, 3).concat(1) : (dark ? [24, 25, 26, 1] : [255, 255, 255, 1]);
  var sampledBackdrop = null;
  try {
    var r = hdEl.getBoundingClientRect();
    var left = Math.max(0, r.left), right = Math.min(window.innerWidth, r.right);
    var y = Math.max(0, r.top) + Math.min(5, (r.bottom - Math.max(0, r.top)) / 2);
    var xs = [0.04, 0.96, 0.5];
    for (var i = 0; i < xs.length; i++) {
      var st = document.elementsFromPoint(left + (right - left) * xs[i], y);
      for (var j = 0; j < st.length; j++) {
        var el = st[j];
        while (el && el.shadowRoot && el.shadowRoot.elementFromPoint) {
          var inner = el.shadowRoot.elementFromPoint(left + (right - left) * xs[i], y);
          if (!inner || inner.getRootNode() !== el.shadowRoot) break;
          el = inner;
        }
        if (!headerContains(hdEl, el)) continue;
        var paint = null;
        for (; el; el = headerParent(el)) {
          if (el === hdEl || el.getBoundingClientRect().width >= (right - left) * 0.6) {
            paint = paintOver(paint, headerPaint(el));
            if (paint && paint[3] >= 0.99) return paint;
          }
          if (el === hdEl) break;
        }
        paint = paintOver(paint, separate);
        var background = base;
        for (var n = j + 1; n < st.length; n++) {
          if (st[n] === hb || headerContains(hdEl, st[n]) || !isVisible(st[n])) continue;
          background = paintOver(headerPaint(st[n]), base);
          if (background !== base) break;
        }
        if (paint) return paintOver(paint, background);
        if (!sampledBackdrop) sampledBackdrop = background;
      }
    }
    var kids = (hdEl.shadowRoot || hdEl).querySelectorAll('*');
    for (var k = 0; k < kids.length && k < 60; k++) {
      var b = headerPaint(kids[k]);
      if (!b || !headerStyleVisible(getComputedStyle(kids[k]))) continue;
      var kr = kids[k].getBoundingClientRect();
      if (kr.width > (right - left) * 0.6 && kr.top <= y && kr.bottom > y) {
        return paintOver(b, paintOver(headerPaint(hdEl), sampledBackdrop || base));
      }
    }
  } catch (e) {}
  if (sampledBackdrop) return sampledBackdrop;
  for (var p = hdEl; p; p = headerParent(p)) {
    var cl = headerPaint(p);
    if (cl) return paintOver(cl, paintOver(separate, base));
  }
  return paintOver(separate, base);
}

var headerLandmarks = 'header,nav,[role~="banner"],[role~="navigation"],[role~="toolbar"]';
var headerHints = '[id*="header" i],[class*="header" i],[id*="topbar" i],[class*="topbar" i],' +
  '[id*="masthead" i],[class*="masthead" i],[id*="top-bar" i],[class*="top-bar" i],' +
  '[id*="navbar" i],[class*="navbar" i],[id*="nav-bar" i],[class*="nav-bar" i]';
var headerOverlays = 'dialog,[role~="dialog"],[role~="alertdialog"],[role~="alert"],[role~="menu"],' +
  '[aria-modal="true"],[popover]';

function headerParent(el){
  return el.assignedSlot || el.parentElement || el.getRootNode().host || null;
}

function headerContains(parent, el){
  for (; el; el = headerParent(el)) if (el === parent) return true;
  return false;
}

function headerStyleVisible(cs){
  return cs.display !== 'none' && cs.visibility === 'visible' &&
    cs.contentVisibility !== 'hidden' && parseFloat(cs.opacity) >= 0.1;
}

function headerName(el){
  return (el.localName + ' ' + el.id + ' ' + (el.getAttribute('class') || '')).replace(/([a-z0-9])([A-Z])/g, '$1-$2');
}

function headerSignal(el){
  if (el.matches('header,[role~="banner"]')) return 5;
  var name = headerName(el);
  if (/(^|[\s_-])(header|mast[-_]?head|top[-_]?bar)(?=$|[\s_-])/i.test(name)) return 4;
  if (el.matches('nav,[role~="navigation"],[role~="toolbar"]') ||
      /(^|[\s_-])nav[-_]?bar(?=$|[\s_-])/i.test(name)) return 3;
  return 0;
}

function transientHeaderUI(el){
  return /(^|[\s_-])(cookie|consent|toast|snackbar|chat[-_]?widget)(?=$|[\s_-])/i.test(headerName(el));
}

function insetLength(value, basis){
  if (value.endsWith('px')) return parseFloat(value);
  if (value.endsWith('%')) return parseFloat(value) * basis / 100;
  if (value === 'auto' || typeof CSSNumericValue === 'undefined') return NaN;
  try {
    return Array.from(CSSNumericValue.parse(value).toSum('px', 'percent').values).reduce(function(total, part){
      return total + part.value * (part.unit === 'percent' ? basis / 100 : 1);
    }, 0);
  } catch (e) { return NaN; }
}

function stickyPin(el, cs, rect){
  var rec = stickyInsets.get(el), authoredTop = cs.top;
  if (rec && ownsInline(el, 'top', rec.applied)) authoredTop = rec.authoredTop;
  if (activeHeader && activeHeader.el === el && ownsInline(el, 'top', activeHeader.applied)) authoredTop = activeHeader.stickyTop;
  if (authoredTop === 'auto') return null;
  var port = document.documentElement, scrollTop = 0, scrollHeight = port.clientHeight;
  for (var p = headerParent(el); p && p !== document.body && p !== document.documentElement; p = headerParent(p)) {
    if (!/^(auto|scroll|hidden|overlay)$/.test(getComputedStyle(p).overflowY)) continue;
    var pr = p.getBoundingClientRect();
    scrollTop = pr.top + p.clientTop;
    scrollHeight = p.clientHeight;
    if (scrollTop + scrollHeight <= Math.max(0, rect.top)) return null;
    port = p;
    break;
  }
  var top = insetLength(authoredTop, scrollHeight);
  if (isNaN(top)) return null;
  var offset = scrollTop;
  if (cs.transform !== 'none' && typeof DOMMatrixReadOnly !== 'undefined') {
    offset += new DOMMatrixReadOnly(cs.transform).m42;
  }
  var translateY = (cs.translate || '').split(' ')[1];
  if (translateY) offset += (parseFloat(translateY) || 0) * (translateY.endsWith('%') ? rect.height / 100 : 1);
  // Khi đã chạm đáy khối chứa, sticky sẽ rời mép trên theo khối đó.
  return rect.top < offset + top - 2 ? null :
    { port: port, start: scrollTop, offset: offset, y: offset + top, authoredTop: authoredTop };
}

// CSS vẫn quyết định lúc phần tử sticky bám và rời khung cuộn. Chỉ đổi ngưỡng top.
var stickyInsets = new Map(), stickyLinks = null;
function syncStickyInsets(targets){
  stickyInsets.forEach(function(rec, el){
    var owned = ownsInline(el, 'top', rec.applied);
    if (owned && targets && targets.has(el)) return;
    if (owned) restoreInline(el, 'top', rec.saved);
    stickyInsets.delete(el);
  });
  if (!targets) return;
  targets.forEach(function(row, el){
    var top = row.target - row.pin.offset;
    var rec = stickyInsets.get(el);
    if (rec && rec.top === top) return;
    if (!rec) {
      rec = { saved: saveInline(el, 'top'), authoredTop: row.pin.authoredTop };
      stickyInsets.set(el, rec);
    }
    el.style.setProperty('top', top + 'px', 'important');
    rec.top = top;
    rec.applied = el.style.getPropertyValue('top');
  });
}

function headerGeometry(el, I, w, h, rows, rowsOnly){
  var cs = getComputedStyle(el);
  var sticky = cs.position === 'sticky' || cs.position === '-webkit-sticky';
  if ((!sticky && (rowsOnly || cs.position !== 'fixed')) || !headerStyleVisible(cs) ||
      el.hasAttribute('data-edge-shift')) return null;
  var r = el.getBoundingClientRect();
  var managed = activeHeader && activeHeader.el === el && ownsInline(el, activeHeader.property, activeHeader.applied);
  var originalTop = r.top - (managed ? activeHeader.delta : 0);
  var authoredTop = managed && sticky ? activeHeader.stickyTop : cs.top;
  // Khi trang rút thanh lên, đo hộp trước phần bù của Summit để nó có thể rời màn hình.
  if (originalTop < -1 && (originalTop + r.height <= 1 || parseFloat(authoredTop) < 0 ||
      ((cs.transform !== 'none' || (cs.translate && cs.translate !== 'none')) &&
       originalTop < (headerRestTop.get(el) ?? originalTop) - 1))) return null;
  var width = Math.min(w, r.right) - Math.max(0, r.left);
  if (r.bottom < 1 || width < 1 || r.height < 1) return null;
  var pin = sticky ? stickyPin(el, cs, r) : null;
  if (sticky && !pin) return null;
  var hd = { el: el, cs: cs, rect: r, originalTop: originalTop, width: width, sticky: sticky, pin: pin };
  if (sticky && rows) rows.set(el, hd);
  if (rowsOnly || r.top > I + 1 || r.bottom < 7 || width < w * 0.45 || r.height < 9 ||
      r.height > h * 0.39 || (pin && (pin.start > I + 1 || pin.y > I + 1))) return null;
  var bandOnly = false;
  for (var p = el; p && p !== document.documentElement; p = headerParent(p)) {
    var style = p === el ? cs : getComputedStyle(p);
    if (parseFloat(style.opacity) < 0.1) return null;
    if (p === document.body) break;
    if (sticky && p.matches('article,aside,table,[role~="complementary"],[role~="grid"]')) return null;
    if (p.matches(headerOverlays) || ((p === el || style.position !== 'static') && transientHeaderUI(p))) {
      if (p !== el || sticky) return null;
      bandOnly = true;
    }
  }
  hd.bandOnly = bandOnly;
  return hd;
}

// Kiểm tra cả chữ, icon và vùng điều hướng trong wrapper, với giới hạn số nút mỗi ứng viên.
function headerContent(el, rect){
  var signal = headerSignal(el), content = false, visited = 0;
  function visit(node, depth){
    if (visited++ >= 64 || depth > 10) return;
    if (node.nodeType === 3) {
      if (/\S/.test(node.data)) content = true;
      return;
    }
    if (node.nodeType === 1) {
      if (node !== el) {
        if (node.matches('script,style,template,noscript') || node.matches(headerOverlays) || transientHeaderUI(node)) return;
        var cs = getComputedStyle(node);
        if (!headerStyleVisible(cs) || cs.position === 'fixed') return;
        if (cs.display !== 'contents') {
          var r = node.getBoundingClientRect();
          if (r.bottom <= Math.max(0, rect.top) || r.top >= rect.bottom ||
              r.right <= Math.max(0, rect.left) || r.left >= Math.min(window.innerWidth, rect.right)) return;
        }
        signal = Math.max(signal, headerSignal(node));
      }
      if (node.matches('svg,img,button,a[href],input,canvas,[role~="button"],[aria-label]:not([aria-label=""])')) content = true;
    }
    if (content && signal >= 4) return;
    if (node.assignedNodes) {
      var assigned = node.assignedNodes({ flatten: true });
      if (assigned.length) {
        for (var i = 0; i < assigned.length && visited < 64; i++) visit(assigned[i], depth + 1);
        return;
      }
    }
    var root = node.shadowRoot || node;
    for (var child = root.firstChild; child && visited < 64; child = child.nextSibling) {
      visit(child, depth + 1);
      if (content && signal >= 4) break;
    }
  }
  visit(el, 0);
  return content ? signal || 1 : 0;
}

var emptyStickyLayout = null;
function resetStickyLayout(){
  syncStickyInsets(null);
  stickyLinks = null;
  emptyStickyLayout = null;
}
function layoutStickyRows(I, header, rows, sample){
  if (!header && !rows.size && !stickyInsets.size) return;
  var targets = new Map(), edges = [], cursor = 0;
  function edge(row, base, placed){
    edges.push({ row: row, before: base + row.rect.height, after: placed + row.rect.height, delta: placed - base });
  }
  function place(row, top, above){
    if (row === header || targets.has(row.el) || top <= row.pin.y + 0.5) return;
    row.target = top;
    targets.set(row.el, row);
    if (above) {
      if (!stickyLinks) stickyLinks = new WeakMap();
      stickyLinks.set(row.el, { above: above.el, parent: headerParent(row.el), port: row.pin.port });
    }
    edge(row, row.pin.y, top);
  }
  function followEdges(){
    while (cursor < edges.length && cursor < 40) {
      var e = edges[cursor++], upper = e.row;
      sample(e.before + 1, upper.rect);
      sample(e.after + 1, upper.rect);
      rows.forEach(function(row){
        if (row === header || targets.has(row.el) || row.pin.y >= e.after - 0.5 ||
            (upper.pin && upper.pin.port !== row.pin.port) || headerContains(row.el, upper.el)) return;
        var a = row.rect, b = upper.rect;
        if (Math.min(a.right, b.right) - Math.max(a.left, b.left) < Math.min(row.width, upper.width) * 0.5) return;
        if (headerContains(upper.el, row.el)) {
          // Hai tầng lồng cùng khung cuộn dùng cùng độ dời. CSS giữ giới hạn của khối cha.
          if (upper.pin) place(row, row.pin.y + e.delta);
          return;
        }
        var link = stickyLinks && stickyLinks.get(row.el);
        var remembered = link && link.above === upper.el && link.parent === headerParent(row.el) &&
          link.port === row.pin.port && a.top <= e.after + 2;
        var gap = row.pin.y - e.before;
        if (Math.abs(gap) <= 2 || remembered || Math.abs(a.top - e.before) <= 2 || Math.abs(a.top - e.after) <= 2) {
          place(row, e.after + Math.max(0, gap), upper);
        }
      });
    }
  }
  if (header) {
    var base = header.pin ? header.pin.y : header.originalTop;
    var placed = headerHasSafePadding(header, I, header.rect.top - base) ? base : Math.max(I, base);
    var bottom = base + header.rect.height;
    if (!rows.size && !stickyInsets.size && emptyStickyLayout && emptyStickyLayout.el === header.el &&
        emptyStickyLayout.bottom === bottom && emptyStickyLayout.placed === placed) return;
    edge(header, base, placed);
    for (var lower = header.fixedBelow; lower; lower = lower.fixedBelow) {
      edge(lower, lower.originalTop, lower.originalTop + placed - base);
    }
    followEdges();
    emptyStickyLayout = rows.size ? null : { el: header.el, bottom: bottom, placed: placed };
  }
  // Xử lý tổ tiên trước khi xét các tầng cùng vùng cuộn và các ô song song.
  Array.from(rows.values()).sort(function(a, b){
    if (a.el !== b.el && headerContains(a.el, b.el)) return -1;
    if (a.el !== b.el && headerContains(b.el, a.el)) return 1;
    return a.pin.y - b.pin.y || a.rect.top - b.rect.top;
  }).forEach(function(row){
    if (row !== header && !targets.has(row.el) && row.pin.start < I && row.pin.y < I &&
        row.rect.top <= I + 1 && !headerHasSafePadding(row, I, row.rect.top - row.pin.y)) {
      place(row, I);
      followEdges();
    }
  });
  if (targets.size || stickyInsets.size) {
    syncStickyInsets(targets);
    if (header) header.rect = header.el.getBoundingClientRect();
  }
}

var headerLandmarkCache = new WeakMap();
function findHeader(I){
  var w = window.innerWidth, h = window.innerHeight, best = null;
  var seen = new Set(), roots = new Set([document]), rows = new Map(), fixedBands = [], rowsOnly = false;
  function consider(el){
    for (; el && el !== document.body && el !== document.documentElement; el = headerParent(el)) {
      if (seen.has(el)) break;
      seen.add(el);
      var hd = headerGeometry(el, I, w, h, rows, rowsOnly);
      if (!hd) continue;
      var signal = headerContent(el, hd.rect);
      if (!signal || (signal < 3 && (hd.width < w * 0.65 || hd.rect.height > h * 0.29))) continue;
      hd.signal = signal;
      if (!hd.sticky) {
        if (hd.originalTop >= -1 && hd.cs.top !== 'auto' && hd.width >= w * 0.65 &&
            hd.rect.height <= h * 0.29) fixedBands.push(hd);
      }
      if (hd.bandOnly) continue;
      if (!best || signal > best.signal || (signal === best.signal &&
          (headerContains(el, best.el) ||
           (!headerContains(best.el, el) && hd.rect.top < best.rect.top - 2)))) best = hd;
    }
  }
  function probe(root, x, y){
    if (!root.elementsFromPoint) return;
    var stack = root.elementsFromPoint(x, y);
    for (var j = 0; j < stack.length; j++) {
      var el = stack[j];
      if (root !== document && el.getRootNode() !== root) continue;
      consider(el);
      if (el.shadowRoot && !roots.has(el.shadowRoot) && roots.size < 12) {
        roots.add(el.shadowRoot);
        observeHeaderRoot(el.shadowRoot);
      }
    }
  }
  var xs = [0.5, 0.08, 0.92, 0.28, 0.72];
  var ys = [1, Math.max(2, I * 0.5), I + 1, I + 7];
  for (var q = 0; q < ys.length; q++) {
    for (var i = 0; i < xs.length; i++) {
      roots.forEach(function(root){ probe(root, w * xs[i], Math.min(h - 1, ys[q])); });
    }
  }
  // elementsFromPoint bỏ qua phần tử có pointer-events:none. Dò thêm các landmark khi mẫu còn mơ hồ.
  if (!best || best.signal < 4) {
    var remaining = 80;
    roots.forEach(function(root){
      if (!remaining) return;
      var cached = headerLandmarkCache.get(root);
      if (!cached || Date.now() - cached.at > 1500) {
        var matches = root.querySelectorAll(headerLandmarks + ',' + headerHints);
        var sample = Array.prototype.slice.call(matches, 0, 80);
        cached = { nodes: sample, at: Date.now() };
        headerLandmarkCache.set(root, cached);
      }
      var nodes = cached.nodes;
      for (var j = 0; j < nodes.length && remaining > 0; j++, remaining--) {
        if (nodes[j].isConnected) consider(nodes[j]);
      }
    });
  }
  if (fixedBands.length && (!best || !best.sticky)) {
    // Xét các mép trước khi Summit dời phần tử. Các hộp phủ lên nhau không tạo thành hàng.
    fixedBands.sort(function(a, b){ return b.originalTop - a.originalTop; });
    if (!best) best = fixedBands[fixedBands.length - 1];
    fixedBands.forEach(function(band){
      if (band === best || Math.abs(band.originalTop + band.rect.height - best.originalTop) > 2 ||
          headerContains(band.el, best.el) || headerContains(best.el, band.el)) return;
      var overlap = Math.min(band.rect.right, best.rect.right) - Math.max(band.rect.left, best.rect.left);
      if (overlap < Math.min(band.width, best.width) * 0.75) return;
      band.fixedBelow = best;
      band.bandOnly = true;
      best = band;
    });
  }
  rowsOnly = true;
  var sampled = new Set();
  layoutStickyRows(I, best, rows, function(y, rect){
    if (y < 0 || y >= h || sampled.size >= 24) return;
    var left = Math.max(0, rect.left), right = Math.min(w, rect.right);
    [0.25, 0.75].forEach(function(fraction){
      var x = left + (right - left) * fraction, key = Math.round(x) + ',' + Math.round(y);
      if (sampled.has(key)) return;
      sampled.add(key);
      roots.forEach(function(root){ probe(root, x, y); });
    });
  });
  return best;
}

// Các phần tử đã hạ xuống: YouTube có thể ghi đè lại style.top của chúng (vd. player về 48px), nên mỗi lần chạy
// kiểm tra lại giá trị thực tế và áp lại phần đã cộng thêm.
var edgeShiftList = [];
function edgeShiftTop(el, baseTop, delta){
  var rec = { el: el, saved: saveInline(el, 'top'), delta: delta, applied: (baseTop + delta) + 'px' };
  el.setAttribute('data-edge-shift', '1');
  el.style.setProperty('top', rec.applied, 'important');
  rec.applied = el.style.getPropertyValue('top');
  edgeShiftList.push(rec);
}
function edgeReassert(){
  try {
    for (var i = edgeShiftList.length - 1; i >= 0; i--) {
      var rec = edgeShiftList[i], el = rec.el;
      if (!el.isConnected) {
        forgetShift(rec);
        edgeShiftList.splice(i, 1);
      } else if (!ownsInline(el, 'top', rec.applied)) {
        var top = parseFloat(getComputedStyle(el).top);
        if (isNaN(top)) continue;
        rec.saved = saveInline(el, 'top');
        el.style.setProperty('top', (top + rec.delta) + 'px', 'important');
        rec.applied = el.style.getPropertyValue('top');
      }
    }
  } catch (e) {}
}

function forgetShift(rec){
  if (ownsInline(rec.el, 'top', rec.applied)) restoreInline(rec.el, 'top', rec.saved);
  rec.el.removeAttribute('data-edge-shift');
  rec.el.removeAttribute('data-edge-hdbg');
}

// Các phần tử fixed/sticky nằm ngay dưới header (vd. player YouTube) cũng phải hạ theo header,
// nếu không header đã hạ xuống dưới thanh trạng thái sẽ đè lên chúng. Chỉ đụng tới phần tử bắt đầu
// trong dải header mới (từ đáy header cũ tới đáy header mới); lớp phủ bắt đầu từ mép trên thì bỏ qua.
function shiftCompanions(hd, delta){
  try {
    for (var row = hd.fixedBelow; row; row = row.fixedBelow) {
      var rowTop = insetLength(row.cs.top, window.innerHeight);
      if (!isNaN(rowTop) && !row.el.hasAttribute('data-edge-shift')) edgeShiftTop(row.el, rowTop, delta);
    }
    var w = window.innerWidth;
    var hr = hd.el.getBoundingClientRect();
    var probeY = hr.bottom - Math.min(6, delta / 2);
    if (probeY <= 0 || probeY >= window.innerHeight) return;
    var xs = [0.5, 0.2, 0.8];
    for (var q = 0; q < xs.length; q++) {
      var st = document.elementsFromPoint(w * xs[q], probeY);
      for (var j = 0; j < st.length; j++) {
        var el = st[j];
        while (el && el !== document.documentElement && el !== document.body) {
          if (headerContains(hd.el, el) || headerContains(el, hd.el)) break;
          var cs = getComputedStyle(el);
          if (cs.position === 'fixed' && cs.display !== 'none') {
            if (!el.hasAttribute('data-edge-shift')) {
              var r = el.getBoundingClientRect();
              var top = parseFloat(cs.top);
              if (!isNaN(top) && r.width > w * 0.6 && r.height > 20 && r.top >= hr.bottom - delta - 4 && r.top < probeY &&
                  !el.matches(headerOverlays) && !transientHeaderUI(el)) {
                edgeShiftTop(el, top, delta);
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
    // Khối fixed/sticky ngoài cùng chứa video mà KHÔNG chứa header: khi đã đăng nhập, YouTube có thể bọc cả header
    // lẫn player trong cùng một khối sticky; bản cũ gặp khối đó là bỏ cuộc nên player không được hạ xuống.
    var host = null;
    for (var el = best.parentElement; el && el !== document.documentElement && el !== document.body; el = el.parentElement) {
      if (el === hd.el || el.contains(hd.el)) continue;
      var cs = getComputedStyle(el);
      if (cs.position === 'fixed' || cs.position === 'sticky') host = el;
    }
    if (!host || host === hd.el || hd.el.contains(host)) return;
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

// Player có thể xuất hiện/được dựng lại SAU lúc header được dò (trang xem video tải khi đã đăng nhập, chuyển video
// kiểu SPA, YouTube thay khối player). Kiểm tra lại định kỳ (tối đa 4 lần/giây), chỉ làm việc khi có video lớn
// đang bị header đè. Thử cách cũ (đổi top của khối sticky/fixed) trước; nếu sau đó vẫn bị đè thì dùng cách dự phòng
// theo hình học: tìm "khung player" (tổ tiên rộng bằng màn hình, cao cỡ player) rồi dời nó bằng transform,
// không phụ thuộc position/top của YouTube.
var lateAt = 0, lateTries = 0, lateVideo = null, edgeVideoTransforms = new Map();
function bigVideo(){
  var vids = document.getElementsByTagName('video'), best = null, area = 0;
  for (var i = 0; i < vids.length; i++) {
    var r = vids[i].getBoundingClientRect();
    if (r.width * r.height > area) { area = r.width * r.height; best = vids[i]; }
  }
  return area < 20000 ? null : best;
}
function playerBox(v, hd){
  var w = window.innerWidth, h = window.innerHeight;
  var vr = v.getBoundingClientRect(), box = null;
  for (var el = v.parentElement; el && el !== document.documentElement && el !== document.body; el = el.parentElement) {
    if (el === hd.el || el.contains(hd.el)) break;
    var r = el.getBoundingClientRect();
    if (r.height > h * 0.6) break;
    if (r.width >= w * 0.9 && r.top <= vr.top + 1 && r.bottom >= vr.bottom - 1) box = el;
  }
  return box;
}
function lateVideoHost(hd, I){
  var now = Date.now();
  if (now - lateAt < 250) return;
  lateAt = now;
  try {
    var v = bigVideo();
    if (!v) return;
    var hb = hd.el.getBoundingClientRect().bottom;
    var vr = v.getBoundingClientRect();
    if (!(vr.top < hb - 4 && vr.top >= I - 4 && vr.bottom > hb)) { lateTries = 0; return; }   // không bị đè
    if (lateVideo !== v) { lateVideo = v; lateTries = 0; }
    if (lateTries >= 4) return;
    lateTries++;
    if (lateTries <= 1) { shiftVideoHost(hd, I); return; }
    var box = playerBox(v, hd);
    if (!box) return;
    var delta = hb - box.getBoundingClientRect().top;
    if (delta < 4) return;
    var cur = getComputedStyle(box).transform;
    var rec = edgeVideoTransforms.get(box);
    if (!rec || !ownsInline(box, 'transform', rec.applied)) {
      rec = { saved: saveInline(box, 'transform') };
      edgeVideoTransforms.set(box, rec);
    }
    box.setAttribute('data-edge-xf', '1');
    box.style.setProperty('transform', 'translateY(' + delta + 'px)' + (cur && cur !== 'none' ? ' ' + cur : ''), 'important');
    rec.applied = box.style.getPropertyValue('transform');
  } catch (e) {}
}

// Nền của header đôi khi là một khối fixed riêng (YouTube: mobile-topbar-header-background, z-index -1)
// nằm cùng vị trí và cao bằng header. Header đã hạ mà nền ở lại chỗ cũ thì chữ/icon trắng nằm trên nền trắng.
function shiftHeaderBackground(hd, delta){
  try {
    var w = window.innerWidth;
    var hr = hd.el.getBoundingClientRect();
    var hH = hr.height, oldTop = hr.top - delta;
    if (hH < 8) return;
    var cands = [], par = hd.el.parentElement, i, j;
    for (var lvl = 0; par && lvl < 2; lvl++, par = par.parentElement) {
      for (i = 0; i < par.children.length && i < 30; i++) cands.push(par.children[i]);
    }
    var st = document.elementsFromPoint(w * 0.5, Math.max(1, oldTop + hH - 4));
    for (j = 0; j < st.length; j++) cands.push(st[j]);
    for (i = 0; i < cands.length; i++) {
      var el = cands[i];
      if (headerContains(hd.el, el) || headerContains(el, hd.el)) continue;
      if (el.hasAttribute('data-edge-shift') || el.hasAttribute('data-edge-hdbg')) continue;
      var cs = getComputedStyle(el);
      if ((cs.position !== 'fixed' && cs.position !== 'sticky') || !headerStyleVisible(cs) ||
          el.matches(headerOverlays + ',' + headerLandmarks) || transientHeaderUI(el)) continue;
      var r = el.getBoundingClientRect(), top = parseFloat(cs.top);
      if (!isNaN(top) && r.width > w * 0.6 && Math.abs(r.top - oldTop) <= 2 && Math.abs(r.height - hH) <= 4 &&
          (bgOf(el) || cs.backgroundImage !== 'none') && !headerContent(el, r)) {
        el.setAttribute('data-edge-hdbg', '1');
        edgeShiftTop(el, top, delta);
        window.__edgeHdBg = el;
      }
    }
  } catch (e) {}
}

var hdCache = null, activeHeader = null, topOverlay = null;
var headerRestTop = new WeakMap();
var watchedHeader = null, headerAttributes = null, headerStateDirty = false;
function watchHeaderState(el){
  if (el === watchedHeader) return;
  if (headerAttributes) headerAttributes.disconnect();
  watchedHeader = el;
  headerStateDirty = false;
  if (!el) return;
  if (!headerAttributes) headerAttributes = new MutationObserver(function(){
    headerStateDirty = true;
    sched();
  });
  for (var p = el; p; p = headerParent(p)) headerAttributes.observe(p, { attributes: true });
}
var searchOverlay = null, searchCheckedAt = 0;

var searchFieldSelector = 'input[type="search"],input[role="searchbox"],input[name="search_query"],' +
  'input[name="search"],input[enterkeyhint="search"],[role~="search"] input,ytm-search-box input,input.searchbox-input';
var youtubePage = /(^|\.)youtube\.com$/i.test(location.hostname);

function foregroundSearchField(field){
  if (!field.isConnected || field.disabled || field.closest('[hidden],[inert],[aria-hidden="true"]') ||
      !headerStyleVisible(getComputedStyle(field))) return null;
  var r = field.getBoundingClientRect();
  if (r.width < 24 || r.height < 8 || r.bottom <= 0 || r.left >= window.innerWidth || r.right <= 0) return null;
  var x = (Math.max(0, r.left) + Math.min(window.innerWidth, r.right)) / 2;
  var y = (Math.max(0, r.top) + Math.min(window.innerHeight, r.bottom)) / 2;
  return document.elementFromPoint(x, y) === field ? r : null;
}

function findSearchOverlay(I){
  var fields = document.querySelectorAll(searchFieldSelector + (youtubePage
    ? ',input#search,ytm-search-box-renderer input,yt-searchbox input' : ''));
  var width = window.innerWidth, height = window.innerHeight;
  for (var i = 0; i < Math.min(fields.length, 48); i++) {
    var field = fields[i], rect = foregroundSearchField(field);
    if (!rect || rect.top > I + 36) continue;
    var mobileYouTube = youtubePage && (location.hostname === 'm.youtube.com' ||
      field.closest('ytm-mobile-topbar-renderer,ytm-search-box,.mobile-topbar-header'));
    var row = null, isOverlay = false, pinned = false;
    for (var p = headerParent(field); p && p !== document.body && p !== document.documentElement; p = headerParent(p)) {
      var cs = getComputedStyle(p), r = p.getBoundingClientRect();
      if (!headerStyleVisible(cs)) { row = null; break; }
      if (cs.position === 'fixed' || cs.position === 'sticky' ||
          (mobileYouTube && cs.position === 'absolute')) pinned = true;
      if (p.matches('dialog,[role~="dialog"],[aria-modal="true"],[popover]')) {
        isOverlay = true;
        if (cs.position === 'absolute') pinned = true;
      }
      if (r.top <= I + 1 && r.bottom >= rect.bottom - 1 &&
          Math.min(width, r.right) - Math.max(0, r.left) >= width * 0.60 &&
          r.height <= Math.min(height * 0.35, 160)) row = p;
    }
    if (row && pinned && (isOverlay || mobileYouTube)) return {el: row, field: field};
  }
  return null;
}

function releaseSearchOverlay(){
  if (!searchOverlay) return;
  var s = searchOverlay;
  if (s.property && ownsInline(s.el, s.property, s.applied)) restoreInline(s.el, s.property, s.saved);
  searchOverlay = null;
  searchCheckedAt = 0;
}

function runSearchOverlay(I){
  if (searchOverlay && (searchOverlay.inset !== I || !foregroundSearchField(searchOverlay.field) ||
      !headerContains(searchOverlay.el, searchOverlay.field) || (searchOverlay.property &&
      !ownsInline(searchOverlay.el, searchOverlay.property, searchOverlay.applied)))) releaseSearchOverlay();
  if (searchOverlay) return searchOverlay.el;
  var now = Date.now();
  if (now - searchCheckedAt < 180) return null;
  var search = findSearchOverlay(I);
  if (!search) { searchCheckedAt = now; return null; }
  releaseTopOverlay();
  var el = search.el, r = el.getBoundingClientRect(), cs = getComputedStyle(el);
  searchOverlay = {el: el, field: search.field, inset: I};
  if (r.top >= I - 0.5 || headerHasSafePadding({el: el, rect: r, cs: cs}, I)) return el;

  // Chỉ dời hàng nhập liệu. Lớp phủ và cách website xử lý focus/đóng vẫn hoạt động bình thường.
  var top = insetLength(cs.top, window.innerHeight);
  var positioned = /^(absolute|fixed|sticky)$/.test(cs.position) && !isNaN(top);
  var property = positioned ? 'top' : 'margin-top';
  var base = positioned ? top : parseFloat(cs.marginTop) || 0;
  searchOverlay.property = property;
  searchOverlay.saved = saveInline(el, property);
  el.style.setProperty(property, (base + I - r.top) + 'px', 'important');
  searchOverlay.applied = el.style.getPropertyValue(property);
  return el;
}

function releaseHeader(){
  if (!activeHeader) return;
  var el = activeHeader.el;
  if (ownsInline(el, activeHeader.property, activeHeader.applied)) restoreInline(el, activeHeader.property, activeHeader.saved);
  if (activeHeader.marked) el.removeAttribute('data-edge-hd');
  activeHeader = null;
  edgeShiftList.forEach(forgetShift);
  edgeShiftList = [];
  edgeVideoTransforms.forEach(function(rec, box){
    if (ownsInline(box, 'transform', rec.applied)) restoreInline(box, 'transform', rec.saved);
    box.removeAttribute('data-edge-xf');
  });
  edgeVideoTransforms.clear();
  lateAt = 0;
  lateTries = 0;
  lateVideo = null;
  window.__edgeHdBg = null;
}

function headerHasSafePadding(hd, inset, shift){
  shift = shift || 0;
  var padding = parseFloat(hd.cs.paddingTop) || 0;
  if (padding < 1 || hd.rect.top - shift + padding + (parseFloat(hd.cs.borderTopWidth) || 0) < inset - 0.5) return false;
  var controls = (hd.el.shadowRoot || hd.el).querySelectorAll('a[href],button,input,select,[role~="button"],[tabindex]');
  for (var i = 0; i < Math.min(controls.length, 40); i++) {
    var control = controls[i], r = control.getBoundingClientRect();
    if (r.width > 1 && r.height > 1 && r.bottom - shift > 0 && r.top - shift < inset - 0.5 &&
        headerStyleVisible(getComputedStyle(control))) return false;
  }
  return true;
}

function alignHeader(hd, I){
  var el = hd.el;
  if (!activeHeader) {
    activeHeader = { el: el, inset: I, sticky: hd.sticky, delta: 0, property: hd.sticky ? 'top' : 'margin-top' };
    headerRestTop.set(el, Math.min(0, hd.originalTop));
  }
  watchHeaderState(el);
  var property = activeHeader.property;
  if (ownsInline(el, property, activeHeader.applied)) return;
  if (hd.rect.top >= I - 0.5 || headerHasSafePadding(hd, I)) {
    activeHeader.delta = 0;
    return;
  }
  // Dời hộp fixed bằng margin để trang vẫn điều khiển top và transition của nó.
  var next = hd.sticky ? I - hd.pin.offset : (parseFloat(hd.cs.marginTop) || 0) + I - hd.rect.top;
  if (hd.sticky && el.getAnimations && el.getAnimations().some(function(a){
    return a.transitionProperty === 'top' || a.transitionProperty === 'inset-block-start';
  })) return;
  activeHeader.saved = saveInline(el, property);
  activeHeader.stickyTop = hd.cs.top;
  el.style.setProperty(property, next + 'px', 'important');
  activeHeader.applied = el.style.getPropertyValue(property);
  if (!hd.bandOnly) {
    el.setAttribute('data-edge-hd', '1');
    activeHeader.marked = true;
  }
  activeHeader.delta = Math.max(0, el.getBoundingClientRect().top - hd.rect.top);
}

function releaseTopOverlay(){
  if (!topOverlay) return;
  var rec = topOverlay;
  if (ownsInline(rec.el, 'padding-top', rec.applied)) restoreInline(rec.el, 'padding-top', rec.padding);
  if (ownsInline(rec.el, 'box-sizing', 'border-box')) restoreInline(rec.el, 'box-sizing', rec.box);
  rec.el.removeAttribute('data-edge-pad');
  topOverlay = null;
}

function runTopOverlay(I){
  var y = I + 2, w = window.innerWidth, hit = null, color = null;
  var xs = [0.5, 0.15, 0.85];
  for (var i = 0; i < xs.length && !hit; i++) {
    var stack = document.elementsFromPoint(w * xs[i], y);
    for (var j = 0; j < stack.length && !hit; j++) {
      var f = pinned(stack[j]);
      if (!f) continue;
      var r = f.getBoundingClientRect();
      if (r.top < I && r.width > w * 0.6 && r.height >= window.innerHeight * 0.45 &&
          headerStyleVisible(getComputedStyle(f))) {
        hit = f;
        color = climb(stack[j]);
      }
    }
  }
  if (topOverlay && (topOverlay.el !== hit || topOverlay.inset !== I)) releaseTopOverlay();
  if (!hit) { report('none'); return; }
  if (!topOverlay) {
    var pad = parseFloat(getComputedStyle(hit).paddingTop) || 0;
    var padding = pad < I - hit.getBoundingClientRect().top - 0.5 ? pad + I : pad;
    topOverlay = { el: hit, inset: I, padding: saveInline(hit, 'padding-top'), box: saveInline(hit, 'box-sizing') };
    hit.setAttribute('data-edge-pad', '1');
    hit.style.setProperty('box-sizing', 'border-box', 'important');
    hit.style.setProperty('padding-top', padding + 'px', 'important');
    topOverlay.applied = hit.style.getPropertyValue('padding-top');
  }
  report(color ? (color[0] + ',' + color[1] + ',' + color[2]) : 'none');
}

function runEdge(){
  if (viewportZoomed()) return;
  var I = topReserveCss();
  if (!I || !document.elementsFromPoint) {
    watchHeaderState(null);
    resetStickyLayout();
    releaseSearchOverlay();
    releaseHeader();
    releaseTopOverlay();
    hdCache = null;
    report('none');
    return;
  }
  try {
    if (headerStateDirty) {
      headerStateDirty = false;
      if (activeHeader && activeHeader.sticky && activeHeader.applied) {
        releaseHeader();
        hdCache = null;
      }
    }
    var search = runSearchOverlay(I);
    if (search) {
      var searchColor = headerColor(search, false);
      report(searchColor ? searchColor.slice(0, 3).join(',') : 'none');
      return;
    }
    var current = null;
    if (activeHeader) {
      if (activeHeader.inset === I && activeHeader.el.isConnected) {
        current = headerGeometry(activeHeader.el, I, window.innerWidth, window.innerHeight);
      }
      if (!current || current.sticky !== activeHeader.sticky) {
        releaseHeader();
        hdCache = null;
        current = null;
      }
    }
    var hd = hdCache && Date.now() - hdCache <= 1500 && current;
    var fresh = !hd;
    if (fresh) {
      hd = findHeader(I);
      var nowSafe = activeHeader && hd && hd.el === activeHeader.el && activeHeader.delta > 0.5 &&
        ownsInline(hd.el, activeHeader.property, activeHeader.applied) && headerHasSafePadding(hd, I, activeHeader.delta);
      if (activeHeader && (!hd || hd.el !== activeHeader.el || nowSafe)) {
        releaseHeader();
        hd = findHeader(I);
      }
      hdCache = hd ? Date.now() : null;
    }
    if (!hd) { runTopOverlay(I); return; }
    releaseTopOverlay();
    alignHeader(hd, I);
    var delta = activeHeader.delta;
    if (fresh && delta > 0.5) {
      shiftHeaderBackground(hd, delta);
      shiftCompanions(hd, delta);
      shiftVideoHost(hd, I);
    }
    edgeReassert();
    if (!fresh && delta > 0.5) lateVideoHost(hd, I);
    var hc = headerColor(hd.el);
    report(hc ? (hc[0] + ',' + hc[1] + ',' + hc[2]) : 'none');
  } catch (e) { report('none'); }
}

function run(){
  if (inFs()) return;
  syncRootReserve();
  if (window.__edgeMode) { runEdge(); }
  else {
    watchHeaderState(null);
    resetStickyLayout();
    releaseSearchOverlay();
    releaseHeader();
    releaseTopOverlay();
    hdCache = null;
    runTop();
  }
}

// Không bỏ qua phần tử bị chính script ghi style (xem drainSelf) và không chạy dò header ở mọi khung hình khi cuộn:
// tối thiểu 80ms giữa hai lần chạy.
var observer = null;
var runLastAt = 0, runTimer = null;

// ---- Video toàn màn hình ----
// Trả lại bố cục website khi vào fullscreen và dò lại sau khi thoát.
function inFs(){ return !!(document.fullscreenElement || document.webkitFullscreenElement); }
var wasFs = false;
function edgeResetAll(){
  watchHeaderState(null);
  resetStickyLayout();
  releaseSearchOverlay();
  releaseHeader();
  releaseTopOverlay();
  invalidateHeader();
  styleCache = new WeakMap();
  last = '';
}
function fsCheck(){
  if (inFs()) {
    if (!wasFs) {
      edgeResetAll();
      releaseBottomBars();
      drainSelf();
    }
    wasFs = true;
    return;
  }
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
  if (headerAttributes) headerAttributes.takeRecords();
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

function bottomPosition(el, cs, height){
  if (cs.position === 'fixed') return true;
  if (cs.position !== 'sticky' || cs.bottom === 'auto') return false;
  // Thanh sticky của vùng cuộn cục bộ chỉ liên quan nếu vùng đó chạm mép dưới WebView.
  for (var p = headerParent(el); p && p !== document.documentElement && p !== document.body; p = headerParent(p)) {
    if (!/^(auto|scroll|hidden|overlay)$/.test(getComputedStyle(p).overflowY)) continue;
    return Math.abs(p.getBoundingClientRect().top + p.clientTop + p.clientHeight - height) <= 2;
  }
  return true;
}

function bottomBarOf(el, height){
  for (; el && el !== document.documentElement && el !== document.body; el = headerParent(el)) {
    var cs = getComputedStyle(el);
    if (headerStyleVisible(cs) && bottomPosition(el, cs, height)) return el;
  }
  return null;
}

function stickyAtBottom(cs, gap, height){
  if (cs.position === 'fixed') return true;
  return Math.abs(gap - insetLength(cs.bottom, height)) <= 3;
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

function ownsInline(el, name, value){
  return !!value && el.style.getPropertyValue(name) === value &&
    el.style.getPropertyPriority(name) === 'important';
}

function isBottomNav(el, H, W, offset, known){
  try {
    if (!el || !el.isConnected || el === document.body || el === document.documentElement) return false;
    var cs = getComputedStyle(el);
    if (!bottomPosition(el, cs, H) || cs.display === 'none' || el.matches(headerOverlays) || transientHeaderUI(el)) return false;
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
  for (; el && el !== document.documentElement && el !== document.body; el = headerParent(el)) {
    var m = navMemo.get(el);
    if (m && m.run === navRun) { if (m.ok) return el; continue; }
    if (m && !m.ok && now - m.t < 700) continue;
    var rec = compactBarRec(el);
    var liftedRec = findRec(el);
    var offset = liftedRec ? currentLift(liftedRec) : 0;
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
  // Bù phần dịch còn lại khi thanh đang chạy animation dịch lên.
  var liftDelta = liftRec ? currentLift - liftRec.target : 0;
  var cx = (r.left + r.right) / 2, cy = (r.top + r.bottom) / 2 + liftDelta;
  var scale = viewportScale();
  var px = isFinite(window.__edgePillX) ? window.__edgePillX / scale : (window.innerWidth || 0) / 2;
  var py = isFinite(window.__edgePillY) ? window.__edgePillY / scale : (window.innerHeight || 0) - 34 / scale;
  var pw = isFinite(window.__edgePillW) && window.__edgePillW > 0 ? window.__edgePillW / scale : (window.innerWidth || 0) * 0.40;
  var ph = isFinite(window.__edgePillH) && window.__edgePillH > 0 ? window.__edgePillH / scale : 25 / scale;
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
  if (rec.animationFrame) { cancelAnimationFrame(rec.animationFrame); rec.animationFrame = null; }
  captureCompactBase(rec);
  rec.hidden = hide;
  el.style.setProperty('transform-origin', '50% 50%', 'important');
  el.style.setProperty('will-change', 'transform, opacity', 'important');
  el.style.setProperty('pointer-events', 'none', 'important');

  if (hide) {
    var target = compactTarget(rec);
    if (reducedMotion.matches) {
      rec.targetState = target;
      finishCompactAnimation(rec, true);
    } else springCompactTo(rec, target, true);
  } else {
    // Bật visibility khi thanh đang ở đúng vị trí pill; spring bung ra về kích thước ban đầu.
    el.style.setProperty('visibility', 'visible', 'important');
    var expanded = {
      x: 0, y: 0, sx: 1, sy: 1, opacity: rec.baseOpacity, radius: rec.baseRadius
    };
    if (reducedMotion.matches) finishCompactAnimation(rec, false);
    else springCompactTo(rec, expanded, false);
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

var reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
var liftProperty = '--summit-edge-lift';
var animateLift = false;
try {
  CSS.registerProperty({name: liftProperty, syntax: '<length>', inherits: false, initialValue: '0px'});
  animateLift = true;
} catch (e) {}

function liftEl(el, target, bottomGap){
  var cs = getComputedStyle(el);
  var translate = cs.translate === 'none' ? [] : cs.translate.match(/(?:[^\s()]|\([^)]*\))+/g) || [];
  var rec = {el: el, cur: 0, target: target, bottomGap: bottomGap,
    savedTranslate: saveInline(el, 'translate'),
    savedTransition: saveInline(el, 'transition'),
    savedLift: saveInline(el, liftProperty)};
  // Chỉ nội suy độ dịch của Summit. Giữ nguyên các trục và giá trị translate do website đặt.
  if (animateLift && !reducedMotion.matches) {
    var transition = cs.transitionDuration.split(',').some(function(d){ return parseFloat(d) > 0; })
      ? cs.transition + ', ' : '';
    el.style.setProperty('transition', transition + liftProperty + ' 250ms cubic-bezier(0.2, 0.8, 0.2, 1)', 'important');
    rec.appliedTransition = el.style.getPropertyValue('transition');
  }
  el.style.setProperty('translate', (translate[0] || '0px') + ' calc(' + (translate[1] || '0px') +
    ' - var(' + liftProperty + ', 0px))' + (translate[2] ? ' ' + translate[2] : ''), 'important');
  rec.appliedTranslate = el.style.getPropertyValue('translate');
  lifted.push(rec);
}

function release(rec){
  var el = rec.el;
  if (ownsInline(el, 'translate', rec.appliedTranslate)) restoreInline(el, 'translate', rec.savedTranslate);
  if (ownsInline(el, 'transition', rec.appliedTransition)) restoreInline(el, 'transition', rec.savedTransition);
  if (ownsInline(el, liftProperty, rec.appliedLift)) restoreInline(el, liftProperty, rec.savedLift);
}

function currentLift(rec){
  return parseFloat(getComputedStyle(rec.el).getPropertyValue(liftProperty)) || 0;
}

function applyLift(){
  for (var i = 0; i < lifted.length; i++) {
    var rec = lifted[i];
    var want = rec.target.toFixed(2) + 'px';
    if (!ownsInline(rec.el, liftProperty, want) && !ownsInline(rec.el, liftProperty, rec.appliedLift)) {
      rec.savedLift = saveInline(rec.el, liftProperty);
    }
    if (rec.appliedLift !== want || !ownsInline(rec.el, liftProperty, want)) {
      rec.el.style.setProperty(liftProperty, want, 'important');
      rec.appliedLift = want;
    }
  }
}

function releaseBottomBars(){
  compactBars.forEach(releaseCompactBar);
  lifted.forEach(release);
  compactBars = [];
  lifted = [];
  navMemo = new WeakMap();
}

function runLift(){
  if (inFs()) return;
  var reserve = (window.__edgeBottom || 0) / viewportScale();
  if (reserve <= 0) {
    if (lifted.length || compactBars.length) releaseBottomBars();
    drainSelf();
    return;
  }
  if (viewportZoomed()) return;
  var H = window.innerHeight, W = window.innerWidth;
  var viewport = window.visualViewport;
  var visibleBottom = viewport ? Math.min(H, viewport.offsetTop + viewport.height) : H;
  var limit = visibleBottom - reserve;
  var keep = [];
  for (var i = 0; i < lifted.length; i++) {
    var rec = lifted[i], el = rec.el;
    var cs = getComputedStyle(el), r = el.getBoundingClientRect();
    var compact = compactBarRec(el);
    if (el.isConnected && ownsInline(el, 'translate', rec.appliedTranslate) &&
        (headerStyleVisible(cs) || (compact && compact.cycleStarted && cs.display !== 'none')) &&
        bottomPosition(el, cs, H) && r.width > 1 && r.height > 1 && r.height < H * 0.45) {
      rec.cur = currentLift(rec);
      if (!compact || !compact.cycleStarted) rec.bottomGap = H - (r.bottom + rec.cur);
      rec.target = H - rec.bottomGap - limit;
      if (rec.target > 0.5 && stickyAtBottom(cs, rec.bottomGap, H)) { keep.push(rec); continue; }
    }
    release(rec);
  }
  lifted = keep;
  if (document.elementsFromPoint) {
    navRun++;
    var ys = Array.from(new Set([H - 3, visibleBottom - 3, visibleBottom - reserve / 2,
      Math.max(H * 0.5, limit + 2), H - 28, H - 60]));
    var xs = [0.08, 0.3, 0.5, 0.7, 0.92];
    for (var a = 0; a < ys.length; a++) {
      for (var b = 0; b < xs.length; b++) {
        var stack = document.elementsFromPoint(W * xs[b], ys[a]);
        for (var c = 0; c < stack.length; c++) {
          var nav = wideBottomNavOf(stack[c], H, W);
          if (nav) registerCompactBar(nav);
          var f = bottomBarOf(stack[c], H);
          if (!f) continue;
          if (findRec(f)) break;
          var covered = false;
          for (var k = 0; k < lifted.length; k++) { if (lifted[k].el.contains(f)) { covered = true; break; } }
          if (covered) break;
          var rect = f.getBoundingClientRect();
          var overlap = rect.bottom - limit;
          if (rect.height >= H * 0.45 || rect.width < 1 || rect.top < H * 0.4 || overlap <= 0.5 ||
              !stickyAtBottom(getComputedStyle(f), H - rect.bottom, H)) continue;
          liftEl(f, overlap, H - rect.bottom);
          break;
        }
      }
    }
  }
  applyLift();
  syncCompactBars();
  drainSelf();
}

function schedLift(){
  if (liftTimer) return;
  var wait = Math.max(0, 250 - (Date.now() - liftLast));
  liftTimer = setTimeout(function(){ liftTimer = null; liftLast = Date.now(); runLift(); }, wait);
}

function invalidateHeader(){
  if (stickyInsets.size) syncStickyInsets(null);
  hdCache = null;
  searchCheckedAt = 0;
  headerLandmarkCache = new WeakMap();
}

function schedAll(){ emptyStickyLayout = null; invalidateHeader(); sched(); schedLift(); schedPageBackground(); }

function viewportChanged(){
  rootReserveDirty = true;
  schedAll();
}

window.__edgeRun = function(){ rootReserveDirty = true; invalidateHeader(); run(); runLift(); schedPageBackground(); };
window.__edgePageBgRun = function(){ runPageBackground(); };
// Đổi tab: native đã bỏ qua báo cáo màu lúc trang này chạy nền, nên xoá bộ nhớ 'last' để báo lại.
window.__edgeForceTop = function(){ last = ''; invalidateHeader(); run(); drainSelf(); };
window.__edgeLift = function(){ runLift(); };
window.addEventListener('scroll', function(){
  sched();
  if (window.__edgeBottom > 0) schedLift();
  schedPageBackground(true);
}, {capture: true, passive: true});

// Hàng ở xa có thể vừa ghim vào header sau một cử chỉ. Dò lại các mép khi cuộn đã dừng.
var stickySettleTimer = null;
window.addEventListener('scrollend', function(){
  if (!window.__edgeMode || !activeHeader) return;
  clearTimeout(stickySettleTimer);
  stickySettleTimer = setTimeout(function(){
    emptyStickyLayout = null;
    hdCache = null;
    sched();
  }, 150);
}, {capture: true, passive: true});

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
window.addEventListener('resize', viewportChanged);
if (window.visualViewport) window.visualViewport.addEventListener('resize', viewportChanged);
window.addEventListener('load', function(event){
  schedPageBackground();
  if (event.target === document || event.target.rel === 'stylesheet') {
    viewportChanged();
  }
}, true);
window.addEventListener('pageshow', viewportChanged);
document.addEventListener('focusin', function(event){
  if (!window.__edgeMode || !event.target.matches || !event.target.matches(searchFieldSelector)) return;
  searchCheckedAt = 0;
  sched();
}, {passive: true});
if (youtubePage) document.addEventListener('click', function(){
  if (!window.__edgeMode) return;
  searchCheckedAt = 0;
  sched();
}, {capture: true, passive: true});
function onHeaderMotionEnd(event){
  if (!window.__edgeMode || inFs()) return;
  var el = event.target;
  if (el.nodeType !== 1 || (el !== (activeHeader && activeHeader.el) &&
      el.getBoundingClientRect().top > topReserveCss() + 1)) return;
  var position = getComputedStyle(el).position;
  if (position === 'sticky' || position === 'fixed' || el.matches(headerLandmarks + ',' + headerHints)) {
    hdCache = null;
    sched();
  }
}
window.addEventListener('transitionend', onHeaderMotionEnd, true);
window.addEventListener('animationend', onHeaderMotionEnd, true);

var observedHeaderRoots = new WeakSet();
function observeHeaderRoot(root){
  if (!observer || observedHeaderRoots.has(root)) return;
  observer.observe(root, {childList: true, subtree: true, attributes: true,
    attributeFilter: ['class', 'style', 'id', 'role', 'hidden', 'open', 'popover', 'aria-modal', 'aria-hidden',
      'search-mode', 'searching']});
  observedHeaderRoots.add(root);
}

try {
  var colorSchemeQuery = window.matchMedia('(prefers-color-scheme: dark)');
  if (colorSchemeQuery.addEventListener) colorSchemeQuery.addEventListener('change', viewportChanged);
  else if (colorSchemeQuery.addListener) colorSchemeQuery.addListener(viewportChanged);
} catch (e) {}

try {
  var obsLast = 0, obsTimer = null;
  observer = new MutationObserver(function(records){
    if (inFs()) return;
    var treeChanged = false;
    for (var i = 0; i < records.length; i++) {
      var target = records[i].target;
      if (target === document.documentElement || target === document.body ||
          target === document.head || target.localName === 'style') rootReserveDirty = true;
      if (records[i].type === 'childList') treeChanged = true;
    }
    if (treeChanged) {
      if (watchedHeader && !watchedHeader.isConnected) watchHeaderState(null);
      schedAll();
      return;
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
      var tt = records[k].target, compact = compactBarRec(tt), lift = findRec(tt);
      if (!compact || !compact.cycleStarted || (lift && !ownsInline(tt, 'translate', lift.appliedTranslate))) foreign = true;
    }
    if (!foreign) return;
    // Gộp thay đổi thuộc tính để dò lại header khi trang đổi trạng thái.
    if (obsTimer) return;
    var wait = Math.max(0, 150 - (Date.now() - obsLast));
    obsTimer = setTimeout(function(){
      obsTimer = null;
      obsLast = Date.now();
      emptyStickyLayout = null;
      invalidateHeader();
      sched();
      schedLift();
    }, wait);
  });
  observeHeaderRoot(document.documentElement);
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
