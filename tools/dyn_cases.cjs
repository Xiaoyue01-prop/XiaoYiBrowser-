// Ca test động (A-E) cho edge_script.js: trang đổi bố cục SAU khi script đã chạy.
// Chạy: node tools/dyn_cases.cjs <repo_root> [script]
const fs = require('fs'), path = require('path');
const { createRequire } = require('node:module');
const { chromium } = createRequire(require.resolve('../eval/package.json'))('playwright');
const root = path.resolve(process.argv[2]);
const scriptPath = path.resolve(process.argv[3] || path.join(root, 'app/src/main/assets/edge_script.js'));
const INSET = 50;
const runSrc = fs.readFileSync(path.join(root, 'eval/run.js'), 'utf8');
const pre = runSrc.match(/const MOCK[\s\S]*?(?=\nasync function runCase)/)[0];
const { MOCK, TAG, MEASURE, BAND } = new Function(pre + '; return {MOCK,TAG,MEASURE,BAND};')();
const template = () => fs.readFileSync(scriptPath, 'utf8').replace('%INSET%', '50').replace('%MODE%', 'true')
  .replace('%BOTTOM%', '0').replace('%SCROLL_BOTTOM%', '90').replace('%COMPACT%', 'false').replace('%PILL_METRICS%', '');
const page = (css, body) => '<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1"><style>html,body{margin:0}body{height:3000px;font:16px sans-serif}' + css + '</style></head><body>' + body + '</body></html>';
const near = (a, b, t) => Math.abs(a - b) <= t;
const HDR = 'header{position:fixed;top:0;left:0;right:0;height:56px;background:rgb(30,30,30);color:#fff;z-index:9}';
const chk = {
  top: (sel, want, name) => ({ name: name || 'top(' + sel + ')≈' + want, run: async (p) => { const m = await p.evaluate(MEASURE, sel); return { ok: !!m && near(m.top, want, 1.5), detail: m && m.top.toFixed(1) }; } }),
  adj: (lo, up) => ({ name: lo + ' right under ' + up, run: async (p) => { const a = await p.evaluate(MEASURE, lo), b = await p.evaluate(MEASURE, up);
    return { ok: !!a && !!b && a.top >= b.bottom - 1.5 && a.top <= b.bottom + 2, detail: a && b ? a.top.toFixed(1) + ' vs ' + b.bottom.toFixed(1) : 'missing' }; } }),
  band: () => ({ name: 'no content under status bar', run: async (p) => { const v = await p.evaluate(BAND, INSET); return { ok: v.length === 0, detail: v.slice(0, 3).join(' | ') }; } }),
};
const cases = [];
// A. Chuyển trang SPA: trang A có header, trang B thêm trình phát ghim dưới header (không reload).
for (const v of ['fixed player', 'sticky player', 'header re-rendered']) {
  const playerCss = v === 'sticky player' ? '#p{position:sticky;top:56px;height:200px;background:#000;color:#fff;z-index:5}' : '#p{position:fixed;top:56px;left:0;right:0;height:200px;background:#000;color:#fff;z-index:5}';
  cases.push({ id: 'A_spa_nav_to_watch [' + v + ']', html: page(HDR + playerCss, '<header>YT <a href="#">search</a></header><main id="m" style="padding-top:56px"><h3>Home</h3><p>videos</p></main>'),
    act: async (p) => { await p.evaluate((v) => {
      history.pushState({}, '', '#watch');
      const m = document.getElementById('m');
      if (v === 'header re-rendered') { const h = document.querySelector('header'); const n = document.createElement('header'); n.innerHTML = 'YT <a href="#">search</a>'; h.replaceWith(n); }
      m.innerHTML = '<div id="p">player <button>play</button></div><div style="height:' + (v === 'sticky player' ? 0 : 200) + 'px"></div><h3>Watch title</h3>'; }, v); },
    checks: [chk.top('header', 50), chk.adj('#p', 'header'), chk.band()] });
}
// B. Vào/thoát toàn màn hình: trang viết lại style inline của header và trình phát khi thoát.
for (const v of ['inline style rewritten', 'class swap']) {
  cases.push({ id: 'B_fullscreen_exit_relayout [' + v + ']',
    html: page(HDR + '#p{position:fixed;top:56px;left:0;right:0;height:200px;background:#000;color:#fff;z-index:5}.fs{top:0!important;height:100vh!important;z-index:99!important}', '<header>YT <a href="#">search</a></header><div id="p">player <button>x</button></div><h3 style="margin-top:300px">Title</h3>'),
    act: async (p) => {
      await p.evaluate((v) => { const h = document.querySelector('header'), pl = document.getElementById('p');
        h.style.display = 'none'; if (v === 'class swap') pl.classList.add('fs'); else pl.setAttribute('style', 'position:fixed;top:0;height:100vh;left:0;right:0;z-index:99'); }, v);
      await p.waitForTimeout(500);
      await p.evaluate((v) => { const h = document.querySelector('header'), pl = document.getElementById('p');
        h.removeAttribute('style'); if (v === 'class swap') pl.classList.remove('fs'); else pl.setAttribute('style', ''); }, v); },
    checks: [chk.top('header', 50), chk.adj('#p', 'header'), chk.band()] });
}
// C. Ba tầng ghim xếp chồng: header fixed, trình phát sticky, thanh chip sticky.
for (const v of ['plain', 'dom order reversed']) {
  const body = v === 'plain'
    ? '<header>YT <a href="#">s</a></header><div style="height:56px"></div><div id="p">player</div><div id="c"><button>All</button><button>Related</button></div><p style="height:2000px">list</p>'
    : '<header>YT <a href="#">s</a></header><div style="height:56px"></div><div id="c"><button>All</button><button>Related</button></div><div id="p">player</div><p style="height:2000px">list</p>';
  const css = HDR + '#p{position:sticky;top:56px;height:200px;background:#000;color:#fff;z-index:5}#c{position:sticky;top:256px;height:48px;background:#222;color:#fff;z-index:4}' + (v === 'plain' ? '' : '#c{top:256px}');
  cases.push({ id: 'C_three_tier_stack [' + v + ']', html: page(css, body),
    act: async (p) => { await p.evaluate(() => scrollTo(0, 800)); },
    checks: [chk.adj('#p', 'header'), chk.adj('#c', '#p'), chk.band()] });
}
// D. Lớp tìm kiếm fixed top:0 chỉ hiện sau khi bấm.
for (const v of ['display toggle', 'appended on click', 'slide-in transform']) {
  const overlayCss = '#so{position:fixed;inset:0;background:#fff;z-index:99}.bar{display:flex;height:56px;background:#fff;align-items:center;border-bottom:1px solid #ddd}.bar input{flex:1}'
    + (v === 'display toggle' ? '#so{display:none}#so.open{display:block}' : v === 'slide-in transform' ? '#so{transform:translateY(-100%);transition:transform .2s}#so.open{transform:none}' : '');
  const overlayHtml = '<div id="so"><div class="bar"><a href="#">back</a><input type="search" placeholder="Search"></div><ul><li>nhac bolero</li></ul></div>';
  cases.push({ id: 'D_search_overlay_on_demand [' + v + ']',
    html: page(HDR + overlayCss, '<header>YT <button id="b">search</button></header><p style="margin-top:80px">feed</p>' + (v === 'appended on click' ? '' : overlayHtml)),
    act: async (p) => { await p.evaluate(({ v, h }) => { if (v === 'appended on click') { document.body.insertAdjacentHTML('beforeend', h); } else document.getElementById('so').classList.add('open'); }, { v, h: overlayHtml }); },
    checks: [chk.top('.bar', 50), chk.band()] });
}
// E. Cấu trúc YouTube thật (từ bản chụp DOM): header fixed luôn có; trình phát đổi static -> fixed top:48px khi html nhận class
// "sticky-player" (JS của trang bật theo scrollY); thanh chip sticky có top = đáy trình phát (48+206=254) do trang tự tính.
for (const v of [['scroll to 4px', [4]], ['scroll to 817px', [817]], ['scroll 817 then back to top then 4', [817, 0, 4]]]) {
  const css = 'body{padding-top:48px}header{position:fixed;top:0;left:0;right:0;height:48px;background:#0f0f0f;color:#fff;z-index:4}'
    + '#pc{height:206px;background:#000;color:#fff;position:relative;z-index:2}html.sticky-player #pc{position:fixed;top:48px;left:0;right:0}'
    + '#ph{height:206px}.chips{height:44px;background:#222;color:#fff;position:relative}html.sticky-player .chips{position:sticky;top:254px;z-index:1}';
  cases.push({ id: 'E_youtube_sticky_player_class_toggle [' + v[0] + ']',
    html: page(css, '<header>YT <a href="#">s</a></header><div id="ph"><div id="pc">player <button>x</button></div></div><div class="chips"><button>All</button><button>Related</button></div><p style="height:2400px">feed</p><script>addEventListener("scroll",function(){document.documentElement.classList.toggle("sticky-player",scrollY>2)},{passive:true})</script>'),
    act: async (p) => { for (const y of v[1]) { await p.evaluate((y) => scrollTo(0, y), y); await p.waitForTimeout(700); } },
    checks: [chk.adj('#pc', 'header'), chk.adj('.chips', '#pc'), chk.band()] });
}
// Ảnh chụp sau khi MutationObserver nhận class, ngay trong lượt đổi trạng thái của trang.
function stackSnapshot() {
  const header = document.querySelector('header').getBoundingClientRect();
  const player = document.querySelector('#pc').getBoundingClientRect();
  const chips = document.querySelector('.chips').getBoundingClientRect();
  return [header.top, player.top - header.bottom, chips.top - player.bottom];
}

const responsiveStack = 'header{height:var(--mast)}:root{--mast:48px;--movie:206px}' +
  '@media(orientation:landscape){:root{--mast:36px;--movie:124px}}' +
  '#pc{position:relative;height:var(--movie)}.pinned #pc{position:fixed;inset-inline:0;top:var(--mast)}' +
  '.chips{height:42px}.pinned .chips{position:sticky;top:calc(var(--mast) + var(--movie))}' +
  '#app{min-height:2400px} #slot{height:var(--movie)}';
const stackBody = '<header>Menu</header><main id="app"><section id="slot"><div id="pc">Player</div></section>' +
  '<div class="chips"><button>All</button></div></main>' +
  '<script>window.samples = []; ' + stackSnapshot.toString() + '</script>';
const aligned = (snapshot) => snapshot.every((n, i) => near(n, i ? 0 : INSET, 1.5));
const snapshotsCheck = { name: 'geometry at each change', run: async p => {
  const samples = await p.evaluate(() => window.samples);
  return {ok: samples.length > 0 && samples.every(aligned), detail: JSON.stringify(samples)};
} };
for (const owner of ['html', 'body', '#app']) {
  cases.push({ id: 'A_spa_pin_first_layout [' + owner + ']', settleMs: 0,
    html: page(HDR + responsiveStack, stackBody),
    act: async p => {
      await p.evaluate(() => { history.pushState({}, '', '#watch'); scrollTo(0, 430); });
      await p.evaluate(async owner => {
        const root = document.querySelector(owner);
        root.classList.add('pinned');
        await Promise.resolve();
        samples.push(stackSnapshot());
        root.classList.remove('pinned');
        await Promise.resolve();
        window.restored = !document.querySelector('#pc').style.top && !document.querySelector('.chips').style.top;
        root.classList.add('pinned');
        await Promise.resolve();
        samples.push(stackSnapshot());
      }, owner);
    },
    checks: [snapshotsCheck, {name: 'in-flow styles restored on unpin', run: async p => ({ok: await p.evaluate(() => window.restored)})}]
  });
}

for (const exitFirst of [false, true]) {
  cases.push({ id: 'B_fullscreen_viewport [' + (exitFirst ? 'exit then resize' : 'resize then exit') + ']',
    settleMs: 0, html: page(HDR + responsiveStack, stackBody.replace('id="app"', 'id="app" class="pinned"')),
    act: async p => {
      await p.evaluate(() => scrollTo(0, 430));
      await p.evaluate(async () => {
        await document.querySelector('#pc').requestFullscreen();
      });
      await p.setViewportSize({width:800, height:390});
      await p.evaluate(() => new Promise(resolve => requestAnimationFrame(resolve)));
      await p.evaluate(() => {
        const rect = document.querySelector('#pc').getBoundingClientRect();
        window.fullBleed = document.fullscreenElement === document.querySelector('#pc') &&
          innerWidth > innerHeight && Math.abs(rect.top) < 1 && Math.abs(rect.bottom - innerHeight) < 1;
      });
      const portrait = {width:390, height:800};
      if (!exitFirst) await p.setViewportSize(portrait);
      await p.evaluate(() => {
        document.addEventListener('fullscreenchange', () => samples.push(stackSnapshot()), {once:true});
        return document.exitFullscreen();
      });
      await p.waitForFunction(() => samples.length === 1);
      if (exitFirst) {
        await p.evaluate(() => {
          addEventListener('resize', () => requestAnimationFrame(() => samples.push(stackSnapshot())), {once:true});
        });
        await p.setViewportSize(portrait);
        await p.waitForFunction(() => samples.length === 2);
      }
    },
    checks: [snapshotsCheck, {name:'fullscreen covers viewport from top', run: async p => ({ok: await p.evaluate(() => window.fullBleed)})}]
  });
}

cases.push({ id: 'D_search_overlay_visible_at_load',
  html: page(HDR + '.sheet{position:fixed;inset:0;z-index:10;background:white}.bar{height:56px;display:flex;align-items:center}',
    '<header>Home</header><section class="sheet"><div class="bar"><a href="#">Back</a><input type="search"></div></section>'),
  checks: [chk.top('.bar', INSET), chk.band()]
});
(async () => {
  const browser = await chromium.launch(process.env.EVAL_CHROMIUM ? { executablePath: process.env.EVAL_CHROMIUM } : {});
  let pass = 0;
  for (const tc of cases) {
    const ctx = await browser.newContext({ viewport: { width: 390, height: 800 }, isMobile: true, hasTouch: true });
    const p = await ctx.newPage(); const errs = []; p.on('pageerror', (e) => errs.push(String(e)));
    await p.setContent(tc.html); await p.evaluate(MOCK); await p.evaluate(TAG);
    await p.evaluate(template()); await p.waitForTimeout(800);
    if (tc.act) await tc.act(p);
    if (tc.settleMs !== 0) await p.waitForTimeout(tc.settleMs ?? 900);
    const res = []; for (const c of tc.checks) { let r; try { r = await c.run(p); } catch (e) { r = { ok: false, detail: 'error ' + e.message }; } res.push([c.name, r]); }
    const ok = res.every(([, r]) => r.ok) && !errs.length; if (ok) pass++;
    console.log((ok ? 'PASS ' : 'FAIL ') + tc.id); if (!ok) { res.filter(([, r]) => !r.ok).forEach(([n, r]) => console.log('     ✗ ' + n + ' ' + (r.detail || ''))); if (errs.length) console.log('     ✗ page error ' + errs[0]); }
    await ctx.close();
  }
  console.log('\n' + pass + '/' + cases.length + ' đạt'); await browser.close();
  if (pass !== cases.length) process.exitCode = 1;
})();
