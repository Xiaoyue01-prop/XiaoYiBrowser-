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

  // ---- Đồng hồ ----
  var clockText = $('clockText');
  var clockSvg = $('clock');
  var greetingEl = $('greeting');
  var is12h = false;
  try { is12h = !!new Intl.DateTimeFormat(undefined, { hour: 'numeric' }).resolvedOptions().hour12; } catch (e) {}

  var lastClock = '';
  function renderClock() {
    var d = new Date();
    var h = d.getHours();
    var text = (is12h ? String(h % 12 || 12) : pad(h)) + ':' + pad(d.getMinutes());
    if (text !== lastClock) {
      lastClock = text;
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
      renderClock();
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

  function setOpen(open) {
    detail.hidden = !open;
    card.classList.toggle('open', open);
    head.setAttribute('aria-expanded', open ? 'true' : 'false');
  }

  function setPicker(open) {
    picker.hidden = !open;
    if (open) {
      query.value = '';
      clear(results);
      setTimeout(function () { query.focus(); }, 50);
    }
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
        clear(results);
        var list = (j && j.results) || [];
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
      })
      .catch(function () {
        if (seq !== searchSeq) return;
        clear(results);
        results.appendChild(el('div', 'none', 'Không tìm được, kiểm tra mạng rồi thử lại'));
      });
  }

  query.addEventListener('input', function () {
    clearTimeout(searchTimer);
    var q = query.value.trim();
    if (q.length < 2) { searchSeq++; clear(results); return; }
    searchTimer = setTimeout(function () { searchCity(q); }, 350);
  });

  // ---- Tương tác ----
  head.addEventListener('click', function () {
    var open = detail.hidden;
    setOpen(open);
    // Chưa chọn thành phố: mở thẳng ô tìm.
    if (open && !city) setPicker(true);
  });

  $('wChange').addEventListener('click', function () {
    setPicker(picker.hidden);
  });

  document.addEventListener('visibilitychange', function () {
    if (!document.hidden) { renderClock(); loadWeather(false); }
  });

  renderClock();
  scheduleTick();
  loadWeather(false);
})();
