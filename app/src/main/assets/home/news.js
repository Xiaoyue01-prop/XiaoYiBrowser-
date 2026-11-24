(() => {
  'use strict';

  const home = document.getElementById('homePanel');
  const news = document.getElementById('newsPanel');
  const homeTab = document.getElementById('homeTab');
  const newsTab = document.getElementById('newsTab');
  const list = document.getElementById('newsList');
  const status = document.getElementById('newsStatus');
  const refresh = document.getElementById('refreshNews');
  const cacheKey = 'summit.news.vn.1';
  const freshFor = 10 * 60 * 1000;
  const dateFormat = new Intl.DateTimeFormat('vi-VN', {
    day: 'numeric', month: 'numeric', hour: '2-digit', minute: '2-digit'
  });
  let stories = [];
  let fetchedAt = 0;
  let busy = false;
  let showingNews = false;
  const positions = { home: 0, news: 0 };
  const track = document.querySelector('.section-switch');

  function articleLink(value) {
    try {
      const u = new URL(value);
      if (u.origin === 'https://news.google.com' && !u.username && !u.password &&
          /^\/(?:rss\/articles|articles|read)\/.+/.test(u.pathname)) return u.href;
    } catch (_) {}
    return null;
  }

  function parseFeed(text) {
    const xml = new DOMParser().parseFromString(text, 'application/xml');
    const items = [];
    const seen = new Set();
    for (const node of xml.querySelectorAll('rss > channel > item')) {
      const field = name => node.querySelector(name)?.textContent.trim() || '';
      const link = articleLink(field('link'));
      const source = field('source');
      const headline = field('title');
      if (!link || !headline || seen.has(link)) continue;
      seen.add(link);
      const suffix = ' - ' + source;
      const title = source && headline.endsWith(suffix) ? headline.slice(0, -suffix.length) : headline;
      items.push({ title, source, link, date: field('pubDate') });
      if (items.length === 40) break;
    }
    if (!items.length) throw new Error('Empty feed');
    return items;
  }

  function render() {
    const fragment = document.createDocumentFragment();
    for (const story of stories) {
      const item = document.createElement('li');
      const a = document.createElement('a');
      a.className = 'news-story';
      a.href = story.link;
      const meta = document.createElement('div');
      meta.className = 'news-meta';
      const publisher = document.createElement('strong');
      publisher.textContent = story.source;
      meta.append(publisher);
      const timestamp = Date.parse(story.date);
      if (Number.isFinite(timestamp)) {
        const time = document.createElement('time');
        time.dateTime = new Date(timestamp).toISOString();
        time.textContent = dateFormat.format(timestamp);
        meta.append(time);
      }
      const title = document.createElement('h2');
      title.textContent = story.title;
      a.append(meta, title);
      item.append(a);
      fragment.append(item);
    }
    list.replaceChildren(fragment);
  }

  function restoreNews() {
    try {
      const cached = JSON.parse(sessionStorage.getItem(cacheKey));
      if (!cached || !Number.isFinite(cached.at) || !Array.isArray(cached.items)) return;
      stories = cached.items.filter(s => s && typeof s.title === 'string' && articleLink(s.link));
      fetchedAt = cached.at;
      render();
    } catch (_) {}
  }

  async function loadNews(force) {
    if (busy) return;
    if (!stories.length) restoreNews();
    if (!force && stories.length && Date.now() - fetchedAt < freshFor) return;
    busy = true;
    refresh.disabled = true;
    news.setAttribute('aria-busy', 'true');
    status.textContent = stories.length ? 'Đang cập nhật…' : 'Đang tải tin…';
    try {
      const response = await fetch('news.rss', { headers: { 'X-Summit-News': '1' }, cache: 'no-store' });
      if (!response.ok) throw new Error('Feed unavailable');
      stories = parseFeed(await response.text());
      fetchedAt = Date.now();
      render();
      status.textContent = '';
      try { sessionStorage.setItem(cacheKey, JSON.stringify({ at: fetchedAt, items: stories })); } catch (_) {}
    } catch (_) {
      status.textContent = stories.length
        ? 'Chưa cập nhật được. Đang hiển thị tin đã tải.'
        : 'Chưa tải được tin. Nhấn Làm mới để thử lại.';
    } finally {
      busy = false;
      refresh.disabled = false;
      news.removeAttribute('aria-busy');
    }
  }

  function select(toNews) {
    if (showingNews === toNews) return;
    positions[showingNews ? 'news' : 'home'] = window.scrollY;
    showingNews = toNews;
    document.body.classList.toggle('show-news', toNews);
    home.inert = toNews;
    news.inert = !toNews;
    homeTab.setAttribute('aria-selected', String(!toNews));
    newsTab.setAttribute('aria-selected', String(toNews));
    homeTab.tabIndex = toNews ? -1 : 0;
    newsTab.tabIndex = toNews ? 0 : -1;
    if (toNews) {
      // Let the selected thumb and the incoming panel paint before building a cold feed.
      requestAnimationFrame(() => requestAnimationFrame(() => {
        if (showingNews) loadNews(false);
      }));
    }
    window.scrollTo(0, positions[toNews ? 'news' : 'home']);
    history.replaceState({ ...history.state, summitNews: toNews }, '');
  }

  // Match SegmentedGlassThumb: preview under the finger, commit on release, cancel on a vertical pan.
  let gesture = null;
  let dropTimer;
  function follow(x) {
    const cell = gesture.cell;
    const local = x - gesture.left;
    const inset = Math.max(0, cell * .1 - 14);
    const offset = Math.min(cell - inset, Math.max(inset, local - cell / 2));
    track.style.setProperty('--thumb-x', offset + 'px');
    track.dataset.hover = local >= cell ? 'news' : 'home';
  }

  track.addEventListener('pointerdown', event => {
    if (!event.isPrimary || event.button !== 0 || gesture) return;
    const rect = track.getBoundingClientRect();
    gesture = { id: event.pointerId, left: rect.left + 4, cell: (rect.width - 8) / 2, start: performance.now() };
    track.setPointerCapture(event.pointerId);
    clearTimeout(dropTimer);
    track.classList.add('lifted');
    follow(event.clientX);
  });
  track.addEventListener('pointermove', event => {
    if (gesture?.id !== event.pointerId) return;
    if (performance.now() - gesture.start >= 140) track.classList.add('tracking');
    follow(event.clientX);
  });
  function finish(event) {
    if (gesture?.id !== event.pointerId) return;
    if (event.type === 'pointerup') {
      follow(event.clientX);
      const toNews = track.dataset.hover === 'news';
      select(toNews);
      (toNews ? newsTab : homeTab).focus({ preventScroll: true });
    }
    const remaining = Math.max(0, 260 - (performance.now() - gesture.start));
    gesture = null;
    track.classList.remove('tracking');
    track.style.removeProperty('--thumb-x');
    delete track.dataset.hover;
    dropTimer = setTimeout(() => track.classList.remove('lifted'), remaining);
  }
  track.addEventListener('pointerup', finish);
  track.addEventListener('pointercancel', finish);
  track.addEventListener('lostpointercapture', finish);
  homeTab.addEventListener('click', () => select(false));
  newsTab.addEventListener('click', () => select(true));
  refresh.addEventListener('click', () => loadNews(true));
  track.addEventListener('keydown', event => {
    if (!['ArrowRight', 'ArrowLeft', 'Home', 'End'].includes(event.key)) return;
    event.preventDefault();
    const toNews = event.key === 'Home' ? false : event.key === 'End' ? true : !showingNews;
    select(toNews);
    (toNews ? newsTab : homeTab).focus({ preventScroll: true });
  });
  if (history.state?.summitNews) {
    // Rebuild the previous feed before the browser restores its saved scroll position.
    restoreNews();
    select(true);
  }
})();
