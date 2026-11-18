(function () {
  'use strict';

  // ---- Cấu hình (sửa tại đây) ----
  // Thành phố cho thời tiết. Mặc định tạm là Hà Nội, đổi tên và tọa độ cho đúng nơi bạn ở.
  var CITY = { name: 'Hà Nội', lat: 21.03, lon: 105.85 };
  // Màu từng chữ số (hồng, tím lavender, tím đậm, xanh núi), lấy từ bảng màu mèo.
  var COLORS = ['#D274B5', '#A99BFF', '#9B3BA8', '#4DA3FF'];
  var CACHE_MS = 30 * 60 * 1000;
  var SVGNS = 'http://www.w3.org/2000/svg';

  // Tắt thời tiết hoàn toàn (không gửi request nào ra ngoài): localStorage.setItem('summit.weather', '0')
  var mem = {};
  var store = {
    get: function (k) {
      try { return localStorage.getItem(k); } catch (e) { return mem[k] || null; }
    },
    set: function (k, v) {
      try { localStorage.setItem(k, v); } catch (e) { mem[k] = v; }
    }
  };

  var clockText = document.getElementById('clockText');
  var clockSvg = document.getElementById('clock');
  var greetingEl = document.getElementById('greeting');
  var is12h = false;
  try { is12h = !!new Intl.DateTimeFormat(undefined, { hour: 'numeric' }).resolvedOptions().hour12; } catch (e) {}

  function pad(n) { return n < 10 ? '0' + n : String(n); }

  // ---- Đồng hồ ----
  var lastClock = '';
  function renderClock() {
    var d = new Date();
    var h = d.getHours();
    var text = (is12h ? String(h % 12 || 12) : pad(h)) + ':' + pad(d.getMinutes());
    if (text !== lastClock) {
      lastClock = text;
      while (clockText.firstChild) clockText.removeChild(clockText.firstChild);
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
  var card = document.getElementById('weather');

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

  function showWeather(cur) {
    try {
      var info = describe(cur.weather_code);
      document.getElementById('wIcon').textContent = info[0];
      document.getElementById('wTemp').textContent = Math.round(cur.temperature_2m) + '°';
      document.getElementById('wDesc').textContent = info[1];
      document.getElementById('wSub').textContent =
        CITY.name + ' · Cảm giác như ' + Math.round(cur.apparent_temperature) + '°';
      card.hidden = false;
    } catch (e) {
      card.hidden = true;
    }
  }

  function loadWeather() {
    if (store.get('summit.weather') === '0') { card.hidden = true; return; }
    var cached = null;
    try { cached = JSON.parse(store.get('summit.w') || 'null'); } catch (e) {}
    if (cached && cached.city === CITY.name && cached.data) {
      showWeather(cached.data);
      if (Date.now() - cached.t < CACHE_MS) return;
    }
    // Làm tròn 2 chữ số: chỉ gửi vị trí cỡ thành phố, không gửi tọa độ chính xác.
    var url = 'https://api.open-meteo.com/v1/forecast' +
      '?latitude=' + CITY.lat.toFixed(2) + '&longitude=' + CITY.lon.toFixed(2) +
      '&current=temperature_2m,apparent_temperature,weather_code&timezone=auto';
    fetch(url, { credentials: 'omit', referrerPolicy: 'no-referrer' })
      .then(function (r) { if (!r.ok) throw new Error('http'); return r.json(); })
      .then(function (j) {
        if (!j || !j.current) throw new Error('bad');
        store.set('summit.w', JSON.stringify({ t: Date.now(), city: CITY.name, data: j.current }));
        showWeather(j.current);
      })
      .catch(function () { /* mất mạng: giữ giá trị cũ nếu có */ });
  }

  document.addEventListener('visibilitychange', function () {
    if (!document.hidden) { renderClock(); loadWeather(); }
  });

  renderClock();
  scheduleTick();
  loadWeather();
})();
