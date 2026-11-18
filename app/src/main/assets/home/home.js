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

  function greeting(h) {
    if (h < 5) return 'Khuya rồi, nghỉ sớm nhé';
    if (h < 11) return 'Chào buổi sáng';
    if (h < 13) return 'Chào buổi trưa';
    if (h < 18) return 'Chào buổi chiều';
    return 'Chào buổi tối';
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
  var head = $('wHead');
  var detail = $('wDetail');
  var picker = $('wPicker');
  var query = $('wQuery');
  var results = $('wResults');

  function describe(code) {
    if (code === 0) return ['☀️', 'Trời quang'];
    if (code === 1 || code === 2) return ['🌤️', 'Ít mây'];
    if (code === 3) return ['☁️', 'Nhiều mây'];
    if (code === 45 || code === 48) return ['🌫️', 'Sương mù'];
    if (code >= 51 && code <= 57) return ['🌦️', 'Mưa phùn'];
    if (code >= 61 && code <= 67) return ['🌧️', 'Mưa'];
    if (code >= 71 && code <= 77) return ['🌨️', 'Tuyết'];
    if (code >= 80 && code <= 82) return ['🌦️', 'Mưa rào'];
    if (code >= 95 && code <= 99) return ['⛈️', 'Dông'];
    return ['☁️', 'Thời tiết'];
  }

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

  var inner = $('wInner');
  var chev = $('wChevron');
  var isOpen = false;

  // Mở/đóng thẻ: spring tiến độ (pS: 0 đóng .. 1 mở) nhân với spring chiều cao nội dung (sS).
  // Chiều cao, độ mờ nội dung và mũi tên xoay đều chạy theo cùng một spring nên luôn khớp nhau.
  function render() {
    detail.style.height = Math.max(0, pS.x * sS.x) + 'px';
    inner.style.opacity = clamp(pS.x * 1.5, 0, 1);
    chev.style.transform = 'rotate(' + (180 * pS.x).toFixed(1) + 'deg)';
  }
  function settled() {
    if (pS.live || sS.live) return;
    detail.style.height = '';
    inner.style.opacity = '';
    if (isOpen) {
      chev.style.transform = 'rotate(180deg)';
    } else {
      detail.hidden = true;
      chev.style.transform = '';
    }
  }
  var pS = new Spring(SETTLE, 0.002, render, settled);
  var sS = new Spring(SETTLE, 0.4, render, settled);

  function setOpen(open) {
    isOpen = open;
    card.classList.toggle('open', open);
    head.setAttribute('aria-expanded', open ? 'true' : 'false');
    if (reduced) {
      detail.hidden = !open;
      chev.style.transform = open ? 'rotate(180deg)' : '';
      return;
    }
    if (open) {
      if (detail.hidden) {
        detail.hidden = false;
        detail.style.height = '0px';
        inner.style.opacity = '0';
        pS.jump(0);
        sS.jump(inner.offsetHeight);
      } else {
        sS.go(inner.offsetHeight);
      }
      pS.setSpec(SETTLE);
      pS.go(1);
    } else {
      pS.setSpec(DISMISS);
      pS.go(0);
    }
  }

  // Đổi nội dung trong thẻ đang mở (ô tìm thành phố, danh sách kết quả): chiều cao đổi bằng spring.
  function resizeOpen(mutate) {
    if (!isOpen || detail.hidden || reduced) { mutate(); return; }
    if (pS.live) {                      // đang mở dở: chỉ đổi đích chiều cao
      mutate();
      sS.go(inner.offsetHeight);
      return;
    }
    var old = inner.offsetHeight;
    pS.jump(1);
    sS.jump(old);
    detail.style.height = old + 'px';
    mutate();
    sS.go(inner.offsetHeight);
  }

  function setPicker(open) {
    resizeOpen(function () {
      picker.hidden = !open;
      if (open) {
        query.value = '';
        clear(results);
      }
    });
    if (open) setTimeout(function () { query.focus(); }, 50);
  }

  function showEmpty() {
    $('wIcon').textContent = '📍';
    $('wTemp').textContent = '';
    $('wDesc').textContent = 'Chọn thành phố';
    $('wSub').textContent = 'Để xem thời tiết ở nơi bạn ở';
    clear($('wHours'));
    clear($('wDays'));
    $('wChange').hidden = true;
    card.hidden = false;
  }

  function showWeather(data) {
    try {
      var cur = data.current;
      var info = describe(cur.weather_code);
      $('wIcon').textContent = info[0];
      $('wTemp').textContent = Math.round(cur.temperature_2m) + '°';
      $('wDesc').textContent = info[1];
      $('wSub').textContent = city.name + ' · Cảm giác như ' + Math.round(cur.apparent_temperature) + '°';
      $('wChange').hidden = false;
      renderHours(data);
      renderDays(data);
      card.hidden = false;
    } catch (e) {
      showEmpty();
    }
  }

  // 6 giờ tới, bắt đầu từ giờ hiện tại (giờ địa phương của thành phố, do timezone=auto).
  function renderHours(data) {
    var box = $('wHours');
    clear(box);
    var hr = data.hourly;
    if (!hr || !hr.time) return;
    var now = String(data.current.time).slice(0, 13) + ':00';
    var start = 0;
    while (start < hr.time.length && hr.time[start] < now) start++;
    for (var i = start; i < Math.min(start + 6, hr.time.length); i++) {
      var col = el('div', 'h');
      col.appendChild(el('span', 't', i === start ? 'Bây giờ' : hr.time[i].slice(11, 16)));
      col.appendChild(el('span', 'i', describe(hr.weather_code[i])[0]));
      col.appendChild(el('span', 'd', Math.round(hr.temperature_2m[i]) + '°'));
      var p = hr.precipitation_probability ? hr.precipitation_probability[i] : null;
      col.appendChild(el('span', 'p', p != null ? p + '%' : ''));
      box.appendChild(col);
    }
  }

  function renderDays(data) {
    var box = $('wDays');
    clear(box);
    var dl = data.daily;
    if (!dl || !dl.time) return;
    for (var i = 0; i < Math.min(5, dl.time.length); i++) {
      var row = el('div', 'day');
      var name = i === 0 ? 'Hôm nay' : DAYS[new Date(dl.time[i] + 'T00:00:00').getDay()];
      row.appendChild(el('span', 'n', name));
      row.appendChild(el('span', 'i', describe(dl.weather_code[i])[0]));
      var m = el('span', 'm', Math.round(dl.temperature_2m_max[i]) + '° ');
      m.appendChild(el('span', null, Math.round(dl.temperature_2m_min[i]) + '°'));
      row.appendChild(m);
      box.appendChild(row);
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
    try { cached = JSON.parse(store.get('summit.w2') || 'null'); } catch (e) {}
    if (cached && cached.key === cityKey(city) && cached.data && cached.data.hourly) {
      showWeather(cached.data);
      if (!force && Date.now() - cached.t < CACHE_MS) return;
    } else {
      showEmpty();
      $('wDesc').textContent = 'Thời tiết';
      $('wSub').textContent = city.name + ' · đang tải…';
      $('wChange').hidden = false;
    }
    // Làm tròn 2 chữ số: chỉ gửi vị trí cỡ thành phố, không gửi tọa độ chính xác.
    var url = 'https://api.open-meteo.com/v1/forecast' +
      '?latitude=' + city.lat.toFixed(2) + '&longitude=' + city.lon.toFixed(2) +
      '&current=temperature_2m,apparent_temperature,weather_code' +
      '&hourly=temperature_2m,precipitation_probability,weather_code' +
      '&daily=weather_code,temperature_2m_max,temperature_2m_min' +
      '&forecast_days=5&timezone=auto';
    var key = cityKey(city);
    fetch(url, { credentials: 'omit', referrerPolicy: 'no-referrer' })
      .then(function (r) { if (!r.ok) throw new Error('http'); return r.json(); })
      .then(function (j) {
        if (!j || !j.current || !j.hourly || !j.daily) throw new Error('bad');
        if (!city || cityKey(city) !== key) return; // người dùng vừa đổi thành phố
        var data = { current: j.current, hourly: trim(j.hourly, 48), daily: j.daily };
        store.set('summit.w2', JSON.stringify({ t: Date.now(), key: key, data: data }));
        showWeather(data);
      })
      .catch(function () {
        // Mất mạng: giữ giá trị cũ nếu có; chưa có gì thì báo nhẹ.
        if (city && $('wSub').textContent.indexOf('đang tải') >= 0) {
          $('wSub').textContent = city.name + ' · không tải được';
        }
      });
  }

  function chooseCity(c) {
    city = { name: c.name, lat: Math.round(c.lat * 100) / 100, lon: Math.round(c.lon * 100) / 100 };
    store.set('summit.city', JSON.stringify(city));
    store.del('summit.w2');
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
        resizeOpen(function () {
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
        resizeOpen(function () {
          clear(results);
          results.appendChild(el('div', 'none', 'Không tìm được, kiểm tra mạng rồi thử lại'));
        });
      });
  }

  query.addEventListener('input', function () {
    clearTimeout(searchTimer);
    var q = query.value.trim();
    if (q.length < 2) { searchSeq++; resizeOpen(function () { clear(results); }); return; }
    searchTimer = setTimeout(function () { searchCity(q); }, 350);
  });

  // ---- Tương tác ----
  head.addEventListener('click', function () {
    var open = !isOpen;
    setOpen(open);
    // Chưa chọn thành phố: mở thẳng ô tìm.
    if (open && !city) setPicker(true);
  });

  $('wChange').addEventListener('click', function () {
    setPicker(picker.hidden);
  });

  document.addEventListener('visibilitychange', function () {
    if (!document.hidden) { renderClock(false); loadWeather(false); }
  });

  renderClock(false);
  scheduleTick();
  loadWeather(false);
})();
