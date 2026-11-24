(function () {
  'use strict';

  // ---- Cấu hình ----
  // Màu từng chữ số (hồng, tím lavender, tím đậm, xanh núi), lấy từ bảng màu mèo.
  var COLORS = ['#D274B5', '#A99BFF', '#9B3BA8', '#4DA3FF'];
  var CACHE_MS = 30 * 60 * 1000;
  var SVGNS = 'http://www.w3.org/2000/svg';
  var DAYS = ['CN', 'Thứ 2', 'Thứ 3', 'Thứ 4', 'Thứ 5', 'Thứ 6', 'Thứ 7'];

  // Tắt thời tiết hoàn toàn (không gửi request nào ra ngoài): localStorage.setItem('summit.weather', '0')
  var mem = {};
  var store = {
    get: function (k) {
      try { return localStorage.getItem(k); } catch (e) { return mem[k] || null; }
    },
    set: function (k, v) {
      try { localStorage.setItem(k, v); } catch (e) { mem[k] = v; }
    },
    del: function (k) {
      try { localStorage.removeItem(k); } catch (e) { delete mem[k]; }
    }
  };

  function $(id) { return document.getElementById(id); }
  function pad(n) { return n < 10 ? '0' + n : String(n); }
  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }
  function clear(node) { while (node.firstChild) node.removeChild(node.firstChild); }

  // ---- Spring (cùng thông số với AnimationSystem.kt của app) ----
  // response = thời gian một chu kỳ (giây), z = damping ratio. stiffness = (2π / response)².
  var SETTLE = { r: 0.50, z: 0.76 };   // bung/mở bề mặt: vượt đích ~4-5% rồi đứng yên
  var DISMISS = { r: 0.40, z: 0.90 };  // đóng lại: gần như không nảy
  var reduced = false;
  try { reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches; } catch (e) {}

  var live = [];
  var rafId = 0;
  var lastT = 0;

  function clamp(v, a, b) { return v < a ? a : (v > b ? b : v); }

  function Spring(spec, eps, onUpdate, onEnd) {
    this.x = 0; this.v = 0; this.target = 0; this.delay = 0; this.live = false;
    this.eps = eps; this.onUpdate = onUpdate; this.onEnd = onEnd;
    this.setSpec(spec);
  }
  Spring.prototype.setSpec = function (spec) {
    var w = 2 * Math.PI / spec.r;
    this.k = w * w;
    this.c = 2 * spec.z * w;
  };
  Spring.prototype.jump = function (x) { this.stop(); this.x = x; this.v = 0; this.target = x; };
  // Đang chạy thì giữ nguyên vị trí và vận tốc, chỉ đổi đích (ngắt giữa chừng vẫn mượt).
  Spring.prototype.go = function (to, delaySec) {
    this.target = to;
    this.delay = delaySec || 0;
    if (!this.live) {
      this.live = true;
      live.push(this);
      if (!rafId) { lastT = 0; rafId = requestAnimationFrame(tick); }
    }
  };
  Spring.prototype.stop = function () {
    this.live = false;
    var i = live.indexOf(this);
    if (i >= 0) live.splice(i, 1);
  };

  function tick(now) {
    var dt = lastT ? Math.min((now - lastT) / 1000, 0.05) : 1 / 60;
    lastT = now;
    var list = live.slice();
    for (var s = 0; s < list.length; s++) {
      var sp = list[s];
      if (!sp.live) continue;
      if (sp.delay > 0) { sp.delay -= dt; continue; }
      var n = Math.max(1, Math.ceil(dt * 240));
      var h = dt / n;
      for (var i = 0; i < n; i++) {
        sp.v += (-sp.k * (sp.x - sp.target) - sp.c * sp.v) * h;
        sp.x += sp.v * h;
      }
      if (Math.abs(sp.x - sp.target) < sp.eps && Math.abs(sp.v) < sp.eps * 10) {
        sp.x = sp.target; sp.v = 0;
        sp.stop();
        sp.onUpdate(sp.x);
        if (sp.onEnd) sp.onEnd();
      } else {
        sp.onUpdate(sp.x);
      }
    }
    rafId = live.length ? requestAnimationFrame(tick) : 0;
  }

  // ---- Đồng hồ ----
  var clockText = $('clockText');
  var clockSvg = $('clock');
  var greetingEl = $('greeting');
  var is12h = false;
  try { is12h = !!new Intl.DateTimeFormat(undefined, { hour: 'numeric' }).resolvedOptions().hour12; } catch (e) {}

  var lastClock = '';

  // Font có lề rất rộng quanh số 1 (lề phải ~19px, lề trái ~12px ở cỡ 160px) nên cặp có số 1
  // trông hở hơn hẳn các cặp khác. Kéo sát thêm bằng dx (đơn vị px của viewBox).
  var KERN_BEFORE_ONE = -5;   // '1' đứng sau một ký tự
  var KERN_AFTER_ONE = -9;    // ký tự đứng ngay sau '1'

  // Khi phút nhảy: chữ số nào đổi thì lăn lên (số cũ trượt lên rồi mờ, số mới trồi từ dưới lên),
  // dùng spring SETTLE như mọi bề mặt khác trong Summit. Chữ số không đổi đứng yên.
  var ROLL = 58;
  var rollG = null;
  var rollSprings = [];
  var rollShown = [];

  function digitColor(str, idx) {
    var di = 0;
    for (var i = 0; i < idx; i++) if (str.charAt(i) !== ':') di++;
    return COLORS[di % COLORS.length];
  }

  function place(node, y, op) {
    node.setAttribute('transform', 'translate(0 ' + y.toFixed(2) + ')');
    node.setAttribute('opacity', op.toFixed(3));
  }

  function glyph(parent, ch, x, color) {
    var t = document.createElementNS(SVGNS, 'text');
    t.setAttribute('class', 'glyph');
    t.setAttribute('x', x);
    t.setAttribute('y', 0);
    t.setAttribute('fill', color);
    t.setAttribute('stroke', color);
    t.setAttribute('stroke-width', '10');
    t.setAttribute('stroke-linejoin', 'round');
    t.textContent = ch;
    parent.appendChild(t);
    return t;
  }

  function endRoll() {
    for (var i = 0; i < rollShown.length; i++) {
      rollShown[i].removeAttribute('fill-opacity');
      rollShown[i].removeAttribute('stroke-opacity');
    }
    rollShown = [];
    if (rollG && rollG.parentNode) rollG.parentNode.removeChild(rollG);
    rollG = null;
    rollSprings = [];
  }

  function cancelRoll() {
    for (var i = 0; i < rollSprings.length; i++) rollSprings[i].stop();
    endRoll();
  }

  function rollGlyphs(oldText, newText) {
    cancelRoll();
    var n = newText.length;
    var off = oldText.length - n;        // so khớp từ bên phải: dấu ':' luôn thẳng hàng
    var spans = clockText.childNodes;
    var g = document.createElementNS(SVGNS, 'g');
    clockSvg.appendChild(g);
    rollG = g;
    var rank = 0;
    for (var i = n - 1; i >= 0; i--) {
      var ch = newText.charAt(i);
      var j = i + off;
      var oc = j >= 0 ? oldText.charAt(j) : '';
      if (ch === ':' || ch === oc) continue;
      var ext;
      try { ext = clockText.getExtentOfChar(i); } catch (e) { continue; }
      var inc = glyph(g, ch, ext.x, digitColor(newText, i));
      var out = (oc && oc !== ':') ? glyph(g, oc, ext.x, digitColor(oldText, j)) : null;
      place(inc, ROLL, 0);
      if (out) place(out, 0, 1);
      var tsp = spans[i];
      tsp.setAttribute('fill-opacity', '0');
      tsp.setAttribute('stroke-opacity', '0');
      rollShown.push(tsp);
      (function (inc, out, delay) {
        var sp = new Spring(SETTLE, 0.002, function (p) {
          place(inc, ROLL * (1 - p), clamp(p * 2.2, 0, 1));
          if (out) place(out, -ROLL * p, clamp(1 - p * 2.2, 0, 1));
        }, function () {
          for (var k = 0; k < rollSprings.length; k++) if (rollSprings[k].live) return;
          endRoll();
        });
        sp.jump(0);
        sp.go(1, delay);                 // so le nhẹ, từ chữ số bên phải sang trái
        rollSprings.push(sp);
      })(inc, out, rank * 0.032);
      rank++;
    }
    if (!rank) endRoll();
  }

  function renderClock(animate) {
    var d = new Date();
    var h = d.getHours();
    var text = (is12h ? String(h % 12 || 12) : pad(h)) + ':' + pad(d.getMinutes());
    if (text !== lastClock) {
      var prev = lastClock;
      lastClock = text;
      cancelRoll();
      clear(clockText);
      var di = 0;
      for (var i = 0; i < text.length; i++) {
        var ch = text.charAt(i);
        var span = document.createElementNS(SVGNS, 'tspan');
        span.textContent = ch;
        var kern = 0;
        if (i > 0 && ch === '1') kern += KERN_BEFORE_ONE;
        if (i > 0 && text.charAt(i - 1) === '1') kern += KERN_AFTER_ONE;
        if (kern) span.setAttribute('dx', kern);
        if (ch === ':') {
          span.setAttribute('fill', 'rgba(128,128,128,0.6)');
          span.setAttribute('stroke', 'none');
        } else {
          var c = COLORS[di % COLORS.length];
          di++;
          span.setAttribute('fill', c);
          // Viền cùng màu, bo tròn góc: chữ dày và tròn hơn như kiểu StandBy.
          span.setAttribute('stroke', c);
          span.setAttribute('stroke-width', '10');
          span.setAttribute('stroke-linejoin', 'round');
        }
        clockText.appendChild(span);
      }
      fitClock();
      if (animate && prev && !reduced && !document.hidden) rollGlyphs(prev, text);
    }
    greetingEl.textContent = greeting(h);
  }

  // Co khung SVG sát chiều ngang chữ để dãy số luôn vừa màn hình (3 hay 4 chữ số đều vậy).
  function fitClock() {
    try {
      // text-anchor: middle quanh x=0, nên khung ngang là [bb.x, bb.x + bb.width].
      var bb = clockText.getBBox();
      clockSvg.setAttribute('viewBox', [bb.x - 14, -128, bb.width + 28, 142].join(' '));
    } catch (e) {}
  }

  var GREETINGS = {
    late: [
      'Khuya rồi, nghỉ sớm nhé',
      'Đêm đã sâu, đừng thức quá muộn nhé',
      'Thế giới đang ngủ, bạn cũng nên nghỉ thôi',
      'Mai còn dài, ngủ một chút đi nhé',
      'Đêm yên tĩnh quá, vẫn còn thức à?'
    ],
    morning: [
      'Chào buổi sáng',
      'Sáng nay thế nào, sẵn sàng chưa?',
      'Một ngày mới, nhẹ nhàng bắt đầu thôi',
      'Chào ngày mới, chúc bạn nhiều niềm vui',
      'Nắng lên rồi, ra khơi thôi nào',
      'Buổi sáng tốt lành nhé'
    ],
    noon: [
      'Chào buổi trưa',
      'Trưa rồi, nhớ ăn uống đầy đủ nhé',
      'Nghỉ tay một chút, nạp lại năng lượng nào',
      'Giữa ngày rồi, thong thả thôi nhé',
      'Bạn đã ăn trưa chưa?'
    ],
    afternoon: [
      'Chào buổi chiều',
      'Chiều rồi, uống ngụm nước nhé',
      'Chiều nay nhẹ nhàng thôi nhé',
      'Nắng đã dịu, cố thêm chút nữa nhé',
      'Một buổi chiều dễ chịu nhé',
      'Gần hết ngày rồi, bạn làm tốt lắm'
    ],
    evening: [
      'Chào buổi tối',
      'Tối rồi, thư giãn thôi nào',
      'Một ngày dài rồi, nghỉ ngơi nhé',
      'Tối nay bạn muốn khám phá gì nào?',
      'Buổi tối êm đềm nhé',
      'Hoàng hôn đã tắt, đèn đã lên'
    ]
  };
  var greetSlot = null, greetText = '';

  function greeting(h) {
    var slot = h < 5 ? 'late' : h < 11 ? 'morning' : h < 13 ? 'noon' : h < 18 ? 'afternoon' : 'evening';
    if (slot !== greetSlot) {
      var list = GREETINGS[slot];
      greetSlot = slot;
      greetText = list[Math.floor(Math.random() * list.length)];
    }
    return greetText;
  }

  // Cập nhật đúng lúc sang phút mới, không polling mỗi giây.
  function scheduleTick() {
    setTimeout(function () {
      renderClock(true);
      scheduleTick();
    }, 60000 - (Date.now() % 60000) + 50);
  }

  // ---- Thời tiết (Open-Meteo, không cần API key) ----
  var card = $('weather');
  var cityBtn = $('wCity');
  var cityNameEl = $('wCityName');
  var pickWrap = $('wPickWrap');
  var pickInner = $('wPickInner');
  var query = $('wQuery');
  var results = $('wResults');
  var banner = $('wBanner');
  var mainCard = $('wCard');
  var daysBox = $('wDays');
  var scene = $('scene');

  function svg(tag, attrs, parent) {
    var e = document.createElementNS(SVGNS, tag);
    for (var k in attrs) if (Object.prototype.hasOwnProperty.call(attrs, k)) e.setAttribute(k, attrs[k]);
    if (parent) parent.appendChild(e);
    return e;
  }

  // Mã thời tiết WMO -> {k: kiểu icon, w: loại cảnh, t: mô tả}
  function describe(code, isDay) {
    if (code === 0) return isDay ? { k: 'sun', w: 'clear', t: 'Trời nắng' } : { k: 'moon', w: 'clear', t: 'Trời quang' };
    if (code === 1 || code === 2) return { k: isDay ? 'partly' : 'partlyN', w: 'partly', t: 'Ít mây' };
    if (code === 3) return { k: 'cloud', w: 'cloudy', t: 'Có mây' };
    if (code === 45 || code === 48) return { k: 'fog', w: 'fog', t: 'Sương mù' };
    if (code >= 51 && code <= 57) return { k: 'drizzle', w: 'rain', t: 'Mưa phùn' };
    if (code >= 61 && code <= 67) return { k: 'rain', w: 'rain', t: 'Mưa' };
    if ((code >= 71 && code <= 77) || code === 85 || code === 86) return { k: 'snow', w: 'snow', t: 'Tuyết' };
    if (code >= 80 && code <= 82) return { k: 'rain', w: 'rain', t: 'Mưa rào' };
    if (code >= 95 && code <= 99) return { k: 'storm', w: 'storm', t: 'Dông' };
    return { k: 'cloud', w: 'cloudy', t: 'Thời tiết' };
  }

  // ---- Icon thời tiết vẽ bằng SVG (phong cách Google) ----
  var C_SUN = '#FFC933', C_MOON = '#CBD3E8', C_CLOUD = '#D3D8E0', C_RAINY = '#8F98A8', C_DROP = '#6EA8FF', C_BOLT = '#FFD84A';

  function cloudShape(parent, fill, dx, dy) {
    var g = svg('g', { fill: fill, transform: 'translate(' + dx + ' ' + dy + ')' }, parent);
    svg('circle', { cx: 16, cy: 27, r: 8 }, g);
    svg('circle', { cx: 25, cy: 21, r: 10 }, g);
    svg('circle', { cx: 35, cy: 27, r: 7 }, g);
    svg('rect', { x: 10, y: 27, width: 31, height: 8, rx: 4 }, g);
  }
  function sunShape(parent, cx, cy, r) {
    var g = svg('g', {}, parent);
    for (var a = 0; a < 8; a++) {
      var rad = a * Math.PI / 4;
      svg('line', {
        x1: (cx + Math.cos(rad) * (r + 3)).toFixed(1), y1: (cy + Math.sin(rad) * (r + 3)).toFixed(1),
        x2: (cx + Math.cos(rad) * (r + 6.5)).toFixed(1), y2: (cy + Math.sin(rad) * (r + 6.5)).toFixed(1),
        stroke: C_SUN, 'stroke-width': 2.4, 'stroke-linecap': 'round'
      }, g);
    }
    svg('circle', { cx: cx, cy: cy, r: r, fill: C_SUN }, g);
  }
  function moonShape(parent, cx, cy, s) {
    svg('path', {
      d: 'M0-10A10 10 0 1 0 10 3A8 8 0 0 1 0-10Z', fill: C_MOON,
      transform: 'translate(' + cx + ' ' + cy + ') scale(' + s + ')'
    }, parent);
  }
  function drop(parent, x, y) {
    svg('path', { d: 'M0-3.5C2.4 0 3.4 1.6 0 4C-3.4 1.6-2.4 0 0-3.5Z', fill: C_DROP, transform: 'translate(' + x + ' ' + y + ')' }, parent);
  }
  function icon(kind, size) {
    var s = svg('svg', { viewBox: '0 0 48 48', width: size, height: size, 'aria-hidden': 'true' });
    switch (kind) {
      case 'sun': sunShape(s, 24, 24, 9); break;
      case 'moon': moonShape(s, 24, 24, 1.9); break;
      case 'partly': sunShape(s, 17, 16, 7.5); cloudShape(s, C_CLOUD, 4, 7); break;
      case 'partlyN': moonShape(s, 17, 16, 1.4); cloudShape(s, C_CLOUD, 4, 7); break;
      case 'fog':
        cloudShape(s, C_CLOUD, 0, -2);
        [38, 42.5].forEach(function (y, i) {
          svg('line', { x1: 10 + i * 4, y1: y, x2: 38 - i * 2, y2: y, stroke: C_CLOUD, 'stroke-width': 2.4, 'stroke-linecap': 'round' }, s);
        });
        break;
      case 'drizzle': cloudShape(s, C_RAINY, 0, -4); drop(s, 19, 40); drop(s, 31, 41); break;
      case 'rain': cloudShape(s, C_RAINY, 0, -4); drop(s, 15, 41); drop(s, 25, 43); drop(s, 35, 41); break;
      case 'snow':
        cloudShape(s, C_CLOUD, 0, -4);
        [15, 25, 35].forEach(function (x, i) { svg('circle', { cx: x, cy: i === 1 ? 43 : 40.5, r: 2.3, fill: '#EAF2FF' }, s); });
        break;
      case 'storm':
        cloudShape(s, C_RAINY, 0, -5);
        svg('path', { d: 'M26 30 19 40H25L22 47 31 36H25.5Z', fill: C_BOLT }, s);
        break;
      default: cloudShape(s, C_CLOUD, 0, 3);
    }
    return s;
  }
  function putIcon(node, kind, size) { clear(node); node.appendChild(icon(kind, size)); }

  // Thành phố do người dùng chọn: {name, lat, lon}. Chưa chọn thì KHÔNG đoán, chỉ mời chọn.
  function loadCity() {
    try {
      var c = JSON.parse(store.get('summit.city') || 'null');
      if (c && typeof c.name === 'string' && isFinite(c.lat) && isFinite(c.lon)) return c;
    } catch (e) {}
    return null;
  }

  var city = loadCity();

  function cityKey(c) { return c.name + '|' + c.lat + '|' + c.lon; }

  // ---- Ô tìm thành phố: chiều cao chạy bằng spring ----
  var pickOpen = false;
  var pk = new Spring(SETTLE, 0.4, function (h) {
    pickWrap.style.height = Math.max(0, h) + 'px';
    pickInner.style.opacity = clamp(h / 36, 0, 1);
  }, function () {
    pickWrap.style.height = '';
    pickInner.style.opacity = '';
    if (!pickOpen) pickWrap.hidden = true;
  });

  function setPicker(open) {
    pickOpen = open;
    cityBtn.classList.toggle('open', open);
    cityBtn.setAttribute('aria-expanded', open ? 'true' : 'false');
    if (open) { query.value = ''; clear(results); }
    if (reduced) { pickWrap.hidden = !open; if (open) query.focus(); return; }
    if (open) {
      pickWrap.hidden = false;
      pickWrap.style.height = '0px';
      pickInner.style.opacity = '0';
      pk.jump(0);
      pk.setSpec(SETTLE);
      pk.go(pickInner.offsetHeight);
      setTimeout(function () { query.focus(); }, 50);
    } else {
      pk.setSpec(DISMISS);
      pk.go(0);
    }
  }

  // Danh sách kết quả đổi kích thước: chiều cao đổi bằng spring.
  function resizePicker(mutate) {
    if (!pickOpen || pickWrap.hidden || reduced) { mutate(); return; }
    if (pk.live) { mutate(); pk.go(pickInner.offsetHeight); return; }
    var old = pickWrap.offsetHeight;
    pk.jump(old);
    pickWrap.style.height = old + 'px';
    mutate();
    pk.go(pickInner.offsetHeight);
  }

  // ---- Cảnh minh họa theo giờ + thời tiết ----
  var imgBg = scene.querySelector('.bg');
  var imgProps = scene.querySelector('.props');
  var imgCat = scene.querySelector('.cat');
  var imgC1 = scene.querySelector('.c1');
  var imgC2 = scene.querySelector('.c2');
  var sunEl = $('sun');
  var moonEl = $('moon');
  var umbEl = $('umb');
  var fx = $('fx');
  for (var di = 0; di < 24; di++) {
    var dEl = document.createElement('i');
    dEl.style.left = (Math.random() * 98).toFixed(1) + '%';
    dEl.style.setProperty('--r', Math.random().toFixed(3));
    fx.appendChild(dEl);
  }

  function filt(dim, sat) {
    return 'brightness(' + (1 - dim).toFixed(2) + ') saturate(' + (1 - sat).toFixed(2) + ')';
  }

  // st: 'day' | 'dusk' | 'night'; wx: clear | partly | cloudy | fog | rain | snow | storm; frac: 0..1 vị trí mặt trời
  function applyScene(st, wx, frac) {
    var night = st === 'night', dusk = st === 'dusk';
    var rainy = wx === 'rain' || wx === 'storm';
    var dim = night ? 0.5 : (dusk ? 0.12 : 0);
    var sat = 0;
    if (wx === 'cloudy') { dim += 0.1; sat += 0.2; }
    else if (rainy) { dim += wx === 'storm' ? 0.34 : 0.22; sat += 0.4; }
    else if (wx === 'snow') { dim += 0.08; sat += 0.25; }
    else if (wx === 'fog') { dim += 0.06; sat += 0.35; }
    dim = Math.min(dim, 0.66);
    var cd = dim + (rainy ? 0.28 : (wx === 'cloudy' ? 0.1 : 0));
    var cs = Math.min(0.9, sat + (rainy ? 0.45 : (wx === 'cloudy' ? 0.2 : 0)));
    imgBg.style.filter = filt(dim, sat);
    imgProps.style.filter = filt(dim * 0.8, sat * 0.7);
    imgCat.style.filter = filt(dim * 0.55, sat * 0.4);
    imgC1.style.filter = imgC2.style.filter = filt(cd, cs);
    var big = rainy ? 1.45 : (wx === 'cloudy' || wx === 'snow' || wx === 'fog' ? 1.25 : (wx === 'partly' ? 1.08 : 1));
    imgC1.style.scale = imgC2.style.scale = String(big);

    var sunOn = !night && (wx === 'clear' || wx === 'partly');
    sunEl.classList.toggle('on', sunOn);
    sunEl.style.left = (10 + 80 * frac).toFixed(1) + '%';
    sunEl.style.top = (30 - 21 * Math.sin(Math.PI * frac)).toFixed(1) + '%';
    var moonOn = night && (wx === 'clear' || wx === 'partly');
    moonEl.classList.toggle('on', moonOn);
    moonEl.style.left = '84%';
    moonEl.style.top = '17%';
    umbEl.classList.toggle('on', rainy);

    scene.className = st + ' ' + wx;
  }

  // ---- Dữ liệu và hiển thị ----
  var D = null;        // dữ liệu thời tiết hiện tại
  var selDay = 0;      // ngày đang chọn (0 = hôm nay)
  var dayChips = [];

  function toMin(s) { s = String(s); return parseInt(s.slice(11, 13), 10) * 60 + parseInt(s.slice(14, 16), 10); }
  function nowIdx(data) {
    var hr = data.hourly;
    var now = String(data.current.time).slice(0, 13) + ':00';
    var i = 0;
    while (i < hr.time.length && hr.time[i] < now) i++;
    return Math.min(i, hr.time.length - 1);
  }
  function hourDay(hr, i) { return hr.is_day ? hr.is_day[i] === 1 : true; }
  function dayName(dl, i) { return DAYS[new Date(dl.time[i] + 'T00:00:00').getDay()]; }

  function dayState(data) {
    var cur = data.current, dl = data.daily;
    var now = toMin(cur.time), sr = toMin(dl.sunrise[0]), ss = toMin(dl.sunset[0]);
    var st = cur.is_day === 1 ? 'day' : 'night';
    if (Math.abs(now - sr) <= 40 || Math.abs(now - ss) <= 40) st = 'dusk';
    return { st: st, frac: clamp((now - sr) / Math.max(1, ss - sr), 0, 1) };
  }

  function renderBanner(data) {
    var hr = data.hourly, cur = data.current, i0 = nowIdx(data);
    var w = describe(cur.weather_code, cur.is_day === 1).w;
    var text = '', kind = 'rain';
    if (w === 'rain' || w === 'storm') text = 'Đang có mưa';
    else if (w === 'snow') { text = 'Đang có tuyết'; kind = 'snow'; }
    else if (hr.precipitation_probability) {
      for (var j = i0 + 1; j <= Math.min(i0 + 12, hr.time.length - 1); j++) {
        if (hr.precipitation_probability[j] >= 40) { text = 'Có thể có mưa vào khoảng ' + hr.time[j].slice(11, 16); break; }
      }
    }
    if (!text) { banner.hidden = true; return; }
    putIcon($('wBannerIcon'), kind, 28);
    $('wBannerText').textContent = text;
    banner.hidden = false;
  }

  function hourCol(label, temp, kind, p) {
    var col = el('div', 'h');
    col.appendChild(el('span', 'd', Math.round(temp) + '°'));
    col.appendChild(el('span', 'p', p != null && p >= 10 ? p + '%' : ''));
    var ic = el('span', 'i'); putIcon(ic, kind, 38); col.appendChild(ic);
    col.appendChild(el('span', 't', label));
    return col;
  }

  function renderSelected() {
    var data = D, cur = data.current, hr = data.hourly, dl = data.daily;
    var box = $('wHours');
    clear(box);
    var info, sc;
    if (selDay === 0) {
      var isDay = cur.is_day === 1;
      info = describe(cur.weather_code, isDay);
      $('wLabel').textContent = 'Lúc này';
      $('wTemp').textContent = Math.round(cur.temperature_2m) + '°';
      $('wSub').textContent = 'Cảm giác như ' + Math.round(cur.apparent_temperature) + '°';
      var i0 = nowIdx(data);
      box.appendChild(hourCol('Hiện tại', cur.temperature_2m, info.k, null));
      for (var i = i0 + 1; i <= i0 + 4 && i < hr.time.length; i++) {
        box.appendChild(hourCol(hr.time[i].slice(11, 16), hr.temperature_2m[i],
          describe(hr.weather_code[i], hourDay(hr, i)).k, hr.precipitation_probability ? hr.precipitation_probability[i] : null));
      }
      sc = dayState(data);
    } else {
      info = describe(dl.weather_code[selDay], true);
      $('wLabel').textContent = dayName(dl, selDay);
      $('wTemp').textContent = Math.round(dl.temperature_2m_max[selDay]) + '°';
      $('wSub').textContent = 'Thấp nhất ' + Math.round(dl.temperature_2m_min[selDay]) + '°';
      [6, 9, 12, 15, 18].forEach(function (h) {
        var k = hr.time.indexOf(dl.time[selDay] + 'T' + pad(h) + ':00');
        if (k < 0) return;
        box.appendChild(hourCol(pad(h) + ':00', hr.temperature_2m[k],
          describe(hr.weather_code[k], true).k, hr.precipitation_probability ? hr.precipitation_probability[k] : null));
      });
      sc = { st: 'day', frac: 0.5 };
    }
    $('wDesc').textContent = info.t;
    putIcon($('wIcon'), info.k, 52);
    applyScene(sc.st, info.w, sc.frac);
    for (var c = 0; c < dayChips.length; c++) {
      dayChips[c].classList.toggle('sel', c === selDay);
      dayChips[c].setAttribute('aria-pressed', c === selDay ? 'true' : 'false');
    }
  }

  function buildDays(data) {
    var dl = data.daily;
    var keep = daysBox.scrollLeft;
    clear(daysBox);
    dayChips = [];
    for (var i = 0; i < dl.time.length; i++) {
      (function (idx) {
        var b = el('button', 'chip');
        b.type = 'button';
        b.appendChild(el('span', 'n', dayName(dl, idx)));
        var ic = el('span', 'i'); putIcon(ic, describe(dl.weather_code[idx], true).k, 30); b.appendChild(ic);
        b.appendChild(el('span', 'm', Math.round(dl.temperature_2m_max[idx]) + '°/' + Math.round(dl.temperature_2m_min[idx]) + '°'));
        b.addEventListener('click', function () { selDay = idx; renderSelected(); });
        daysBox.appendChild(b);
        dayChips.push(b);
      })(i);
    }
    daysBox.scrollLeft = keep;
  }

  function showEmpty(status) {
    cityNameEl.textContent = city ? city.name + (status ? ' · ' + status : '') : 'Chọn thành phố';
    banner.hidden = true;
    mainCard.hidden = true;
    daysBox.hidden = true;
    D = null;
    card.hidden = false;
  }

  function showWeather(data) {
    try {
      if (!data.daily.sunrise) throw new Error('old');
      D = data;
      if (selDay >= data.daily.time.length) selDay = 0;
      cityNameEl.textContent = city.name;
      renderBanner(data);
      buildDays(data);
      renderSelected();
      mainCard.hidden = false;
      daysBox.hidden = false;
      card.hidden = false;
    } catch (e) {
      showEmpty();
    }
  }

  function trim(j, n) {
    var out = {};
    for (var k in j) if (Object.prototype.hasOwnProperty.call(j, k)) out[k] = j[k] ? j[k].slice(0, n) : j[k];
    return out;
  }

  function loadWeather(force) {
    if (store.get('summit.weather') === '0') { card.hidden = true; return; }
    if (!city) { showEmpty(); return; }
    var cached = null;
    try { cached = JSON.parse(store.get('summit.w3') || 'null'); } catch (e) {}
    if (cached && cached.key === cityKey(city) && cached.data && cached.data.hourly && cached.data.daily && cached.data.daily.sunrise) {
      showWeather(cached.data);
      if (!force && Date.now() - cached.t < CACHE_MS) return;
    } else {
      showEmpty('đang tải…');
    }
    // Làm tròn 2 chữ số: chỉ gửi vị trí cỡ thành phố, không gửi tọa độ chính xác.
    var url = 'https://api.open-meteo.com/v1/forecast' +
      '?latitude=' + city.lat.toFixed(2) + '&longitude=' + city.lon.toFixed(2) +
      '&current=temperature_2m,apparent_temperature,weather_code,is_day' +
      '&hourly=temperature_2m,precipitation_probability,weather_code,is_day' +
      '&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset' +
      '&forecast_days=7&timezone=auto';
    var key = cityKey(city);
    fetch(url, { credentials: 'omit', referrerPolicy: 'no-referrer' })
      .then(function (r) { if (!r.ok) throw new Error('http'); return r.json(); })
      .then(function (j) {
        if (!j || !j.current || !j.hourly || !j.daily) throw new Error('bad');
        if (!city || cityKey(city) !== key) return; // người dùng vừa đổi thành phố
        var data = { current: j.current, hourly: trim(j.hourly, 168), daily: j.daily };
        store.set('summit.w3', JSON.stringify({ t: Date.now(), key: key, data: data }));
        showWeather(data);
      })
      .catch(function () {
        // Mất mạng: giữ giá trị cũ nếu có; chưa có gì thì báo nhẹ.
        if (city && !D) cityNameEl.textContent = city.name + ' · không tải được';
      });
  }

  function chooseCity(c) {
    city = { name: c.name, lat: Math.round(c.lat * 100) / 100, lon: Math.round(c.lon * 100) / 100 };
    store.set('summit.city', JSON.stringify(city));
    store.del('summit.w3');
    selDay = 0;
    setPicker(false);
    loadWeather(true);
  }

  // ---- Tìm thành phố (Open-Meteo Geocoding) ----
  var searchSeq = 0;
  var searchTimer = null;

  function searchCity(q) {
    var seq = ++searchSeq;
    var url = 'https://geocoding-api.open-meteo.com/v1/search?name=' + encodeURIComponent(q) +
      '&count=6&language=vi&format=json';
    fetch(url, { credentials: 'omit', referrerPolicy: 'no-referrer' })
      .then(function (r) { if (!r.ok) throw new Error('http'); return r.json(); })
      .then(function (j) {
        if (seq !== searchSeq) return;
        var list = (j && j.results) || [];
        resizePicker(function () {
          clear(results);
          if (!list.length) { results.appendChild(el('div', 'none', 'Không tìm thấy thành phố này')); return; }
          list.forEach(function (r) {
            var b = el('button', null, r.name);
            var extra = [r.admin1, r.country].filter(function (x) { return x && x !== r.name; }).join(', ');
            if (extra) b.appendChild(el('span', null, ' · ' + extra));
            b.type = 'button';
            b.addEventListener('click', function () {
              chooseCity({ name: r.name, lat: r.latitude, lon: r.longitude });
            });
            results.appendChild(b);
          });
        });
      })
      .catch(function () {
        if (seq !== searchSeq) return;
        resizePicker(function () {
          clear(results);
          results.appendChild(el('div', 'none', 'Không tìm được, kiểm tra mạng rồi thử lại'));
        });
      });
  }

  query.addEventListener('input', function () {
    clearTimeout(searchTimer);
    var q = query.value.trim();
    if (q.length < 2) { searchSeq++; resizePicker(function () { clear(results); }); return; }
    searchTimer = setTimeout(function () { searchCity(q); }, 350);
  });

  // ---- Tương tác ----
  cityBtn.addEventListener('click', function () { setPicker(!pickOpen); });

  document.addEventListener('visibilitychange', function () {
    if (!document.hidden) { renderClock(false); loadWeather(false); }
  });

  // ---- Mèo: đuôi vẫy liên tục, thỉnh thoảng gãi má (luân phiên trái/phải) ----
  (function catIdle() {
    var box = $('cat');
    if (!box || reduced) return;
    var baseImg = box.querySelector('.cb');
    var cv = box.querySelector('.cc');
    var ctx = cv && cv.getContext && cv.getContext('2d');
    var paws = { L: $('pawL'), R: $('pawR') };
    if (!ctx || !paws.L || !paws.R) return;

    var W = 900, H = 457, TAIL = 360, STEP = 4;
    var MAXA = { L: -25, R: 33 };       // góc giơ chân tối đa (độ)
    var DUR = 2.6;                      // một lần gãi: giơ 0.45s, cọ 1.65s, hạ 0.5s
    var img = new Image();
    var clock = 0, last = 0, rafId = 0;
    var scratchAt = -1, nextAt = 2.2, side = 'L';
    var onScreen = true;

    function easeOut(x) { return 1 - Math.pow(1 - x, 3); }
    function amount(t) {
      if (t < 0.45) return easeOut(t / 0.45);
      if (t < DUR - 0.5) return 1 + 0.15 * Math.sin((t - 0.45) / (DUR - 0.95) * Math.PI * 8);
      return 1 - easeOut((t - (DUR - 0.5)) / 0.5);
    }
    function setPaw(s, v) { paws[s].style.transform = v ? 'rotate(' + (MAXA[s] * v).toFixed(2) + 'deg)' : ''; }

    function size() {
      var d = Math.min(window.devicePixelRatio || 1, 2.5);
      cv.width = Math.round(box.clientWidth * d);
      cv.height = Math.round(box.clientHeight * d);
    }

    function draw() {
      var k = cv.width / W;
      ctx.clearRect(0, 0, cv.width, cv.height);
      // Đuôi: cắt ảnh thành cột mỏng, cột càng xa gốc đuôi càng lệch dọc nhiều.
      for (var x = 0; x < TAIL; x += STEP) {
        var f = (TAIL - x) / TAIL;
        var dy = 17 * Math.pow(f, 1.6) * Math.sin(clock * 4.2 - f * 2.4);
        ctx.drawImage(img, x, 0, STEP + 0.8, H, x * k, dy * k, (STEP + 0.8) * k, H * k);
      }
      ctx.drawImage(img, TAIL, 0, W - TAIL, H, TAIL * k, 0, (W - TAIL) * k, H * k);
    }

    function step(now) {
      rafId = 0;
      var dt = last ? Math.min((now - last) / 1000, 0.05) : 0;
      last = now;
      clock += dt;
      draw();
      if (scratchAt < 0 && clock >= nextAt) scratchAt = clock;
      if (scratchAt >= 0) {
        var t = clock - scratchAt;
        if (t > DUR) {
          setPaw(side, 0);
          scratchAt = -1;
          nextAt = clock + 4 + Math.random() * 4;
          side = side === 'L' ? 'R' : 'L';
        } else setPaw(side, amount(t));
      }
      run();
    }
    function run() {
      if (!rafId && onScreen && !document.hidden) rafId = requestAnimationFrame(step);
    }
    function stop() {
      if (rafId) { cancelAnimationFrame(rafId); rafId = 0; }
      last = 0;
    }

    img.onload = function () {
      size();
      draw();
      cv.hidden = false;
      baseImg.style.visibility = 'hidden';
      run();
    };
    img.src = baseImg.currentSrc || baseImg.src;

    document.addEventListener('visibilitychange', function () { document.hidden ? stop() : run(); });
    try {
      new ResizeObserver(function () { if (img.complete) { size(); draw(); } }).observe(box);
      new IntersectionObserver(function (es) {
        onScreen = es[es.length - 1].isIntersecting;
        onScreen ? run() : stop();
      }).observe(box);
    } catch (e) {}
  })();

  store.del('summit.w2');
  renderClock(false);
  scheduleTick();
  loadWeather(false);
})();
