#!/usr/bin/env node
// Bộ chấm điểm tự động cho edge_script.js. Chạy: node eval/run.js [--script <file>] [--cases <dir>] [--json <file>] [--verbose] [--write-baseline]
// Cần: npm i -D playwright && npx playwright install chromium   (hoặc đặt EVAL_CHROMIUM=/đường/dẫn/chromium)
const fs = require('fs'), path = require('path'), crypto = require('crypto');
let chromium;
try { chromium = require('playwright').chromium; } catch (e) {
  console.error('Thiếu module "playwright". Chạy: npm i -D playwright && npx playwright install chromium'); process.exit(3);
}
const { INSET } = require('./lib');

const args = process.argv.slice(2);
const opt = (n, d) => { const i = args.indexOf('--' + n); return i >= 0 ? args[i + 1] : d; };
const flag = (n) => args.includes('--' + n);
const root = path.resolve(__dirname, '..');
const scriptPath = path.resolve(opt('script', path.join(root, 'app/src/main/assets/edge_script.js')));
const casesDir = path.resolve(opt('cases', path.join(__dirname, 'cases')));
const verbose = flag('verbose');
const SCROLL_BOTTOM = 90;

function loadCases(dir) {
  return fs.readdirSync(dir).filter((f) => f.endsWith('.js')).sort().flatMap((f) => require(path.join(dir, f)));
}
function template(bottom) {
  return fs.readFileSync(scriptPath, 'utf8').replace('%INSET%', String(INSET)).replace('%MODE%', 'true')
    .replace('%BOTTOM%', String(bottom)).replace('%SCROLL_BOTTOM%', String(SCROLL_BOTTOM)).replace('%COMPACT%', 'false').replace('%PILL_METRICS%', '');
}
function integrity() {
  const h = crypto.createHash('sha256');
  const files = [path.join(__dirname, 'run.js'), path.join(__dirname, 'lib.js')]
    .concat(fs.readdirSync(path.join(__dirname, 'cases')).sort().map((f) => path.join(__dirname, 'cases', f)));
  files.forEach((f) => h.update(fs.readFileSync(f)));
  return h.digest('hex').slice(0, 16);
}

const MOCK = () => { window.__calls = []; window.EdgeBridge = new Proxy({}, { get: (t, k) => (...a) => window.__calls.push([k, ...a]) }); };
const TAG = () => { let n = 0; document.querySelectorAll('*').forEach((e) => e.setAttribute('data-eval-id', String(n++))); };
const SNAP = () => { const o = {}; document.querySelectorAll('[data-eval-id]').forEach((e) => {
  const s = (e.getAttribute('style') || '').trim(); const d = Array.from(e.attributes).filter((a) => /^data-edge/.test(a.name)).map((a) => a.name).sort().join(',');
  o[e.getAttribute('data-eval-id')] = s + '|' + d; }); return o; };
const MEASURE = (sel) => { const el = document.querySelector(sel); if (!el) return null; const r = el.getBoundingClientRect(), s = visualViewport.scale;
  return { top: r.top * s, bottom: r.bottom * s, left: r.left * s, gapBottom: innerHeight - r.bottom, scale: s }; };
const BAND = (inset) => { const sc = visualViewport.scale, out = [];
  const pinned = (el) => { for (let e = el; e && e !== document.documentElement; e = e.parentElement || (e.getRootNode() && e.getRootNode().host)) { const p = getComputedStyle(e).position; if (p === 'fixed' || p === 'sticky') return true; } return false; };
  const walk = (rootNode) => rootNode.querySelectorAll('*').forEach((el) => {
    if (el.shadowRoot) walk(el.shadowRoot);
    if (el.matches('script,style,meta,link,head,template,title')) return;
    const cs = getComputedStyle(el); if (cs.display === 'none' || cs.visibility !== 'visible' || parseFloat(cs.opacity) < 0.1) return;
    const text = Array.from(el.childNodes).some((n) => n.nodeType === 3 && /\S/.test(n.data));
    if (!text && !el.matches('a[href],button,input,select,textarea,svg,img')) return;
    let r = null;
    if (text && !el.matches('a[href],button,input,select,textarea,svg,img')) {
      // Đo vị trí chữ thật (không phải hộp phần tử, vì hộp có thể có padding chừa chỗ).
      const rg = document.createRange(); let u = null;
      Array.from(el.childNodes).forEach((n) => { if (n.nodeType === 3 && /\S/.test(n.data)) { rg.selectNodeContents(n); const b = rg.getBoundingClientRect(); if (b.width > 0 && b.height > 0) u = u ? { top: Math.min(u.top, b.top), bottom: Math.max(u.bottom, b.bottom) } : { top: b.top, bottom: b.bottom }; } });
      r = u && { top: u.top, bottom: u.bottom, width: 1, height: 1 };
    } else { r = el.getBoundingClientRect(); }
    if (!r || r.width < 1 || r.height < 1) return;
    const top = r.top * sc, bottom = r.bottom * sc;
    if (bottom > 1 && top < inset - 1 && pinned(el)) out.push(el.localName + (el.id ? '#' + el.id : '') + ' y=' + top.toFixed(0) + '..' + bottom.toFixed(0));
  });
  walk(document); return out; };

async function runCase(browser, tc, bottomDefault) {
  const ctx = await browser.newContext({ viewport: { width: 390, height: 800 }, isMobile: true, hasTouch: true });
  const p = await ctx.newPage(); const errs = [];
  p.on('pageerror', (e) => errs.push(String(e)));
  await p.setContent(tc.html); await p.evaluate(MOCK); await p.evaluate(TAG);
  const initial = {}; for (const s of tc.watch || []) initial[s] = await p.evaluate(MEASURE, s);
  const before = await p.evaluate(SNAP);
  const c = { initial,
    measure: (s) => p.evaluate(MEASURE, s),
    band: () => p.evaluate(BAND, INSET),
    lastColor: async () => { const calls = await p.evaluate(() => window.__calls.filter((x) => /TopColor/.test(x[0]))); const l = calls[calls.length - 1];
      return l && l[0] === 'setTopColor' ? l.slice(1) : null; } };
  const res = [];
  const runChecks = async (when) => { for (const ck of (tc.checks || []).filter((k) => k.when === when)) {
    let r; try { r = await ck.run(c); } catch (e) { r = { ok: false, detail: 'error: ' + e.message }; }
    res.push({ name: ck.name, when, cat: ck.cat || tc.cat, ok: !!r.ok, detail: r.detail }); } };
  try {
    await p.evaluate(template(tc.bottom != null ? tc.bottom : 0));
    await p.waitForTimeout(tc.wait || 800); await runChecks('load');
    if (tc.scrollFn) await p.evaluate(tc.scrollFn); else await p.evaluate(() => window.scrollTo(0, 600));
    await p.waitForTimeout(700); await runChecks('scrolled');
    await p.evaluate(() => { window.__edgeMode = false; window.__edgeBottom = 0; window.__edgeScrollBottom = 0; if (window.__edgeRun) window.__edgeRun(); if (window.__edgeLift) window.__edgeLift(); });
    await p.waitForTimeout(400);
    const after = await p.evaluate(SNAP); const bad = Object.keys(before).filter((k) => before[k] !== after[k]);
    res.push({ name: 'state restored after mode off', when: 'restored', cat: 'X', ok: bad.length === 0, detail: bad.slice(0, 2).map((k) => k + ':' + after[k]).join(' ') });
  } catch (e) { res.push({ name: 'case crashed', when: 'load', cat: tc.cat, ok: false, detail: String(e.message).slice(0, 120) }); }
  res.push({ name: 'no page errors', when: 'load', cat: 'X', ok: errs.length === 0, detail: errs[0] || '' });
  await ctx.close(); return res;
}

async function perf(browser) {
  const ctx = await browser.newContext({ viewport: { width: 390, height: 800 }, isMobile: true, hasTouch: true });
  const p = await ctx.newPage(); let rows = ''; for (let i = 0; i < 6000; i++) rows += '<div class="row"><span>item ' + i + '</span><a href="#">l</a></div>';
  await p.setContent('<meta name="viewport" content="width=device-width,initial-scale=1"><style>body{margin:0}header{position:fixed;top:0;left:0;right:0;height:56px;background:#33c;color:#fff}</style><header>H' + '<a href="#">x</a>'.repeat(40) + '</header>' + rows);
  await p.evaluate(MOCK); await p.evaluate(template(0)); await p.waitForTimeout(500);
  const run = await p.evaluate(() => { const t = performance.now(); for (let i = 0; i < 30; i++) window.__edgeRun(); return (performance.now() - t) / 30; });
  const frame = await p.evaluate(async () => { const t = performance.now(); for (let i = 0; i < 60; i++) { window.scrollTo(0, i * 40); await new Promise((r) => requestAnimationFrame(r)); } return (performance.now() - t) / 60; });
  await ctx.close();
  return [{ name: '__edgeRun avg ≤ 20ms @6000 nodes', when: 'perf', cat: 'P', ok: run <= 20, detail: run.toFixed(1) + 'ms' },
          { name: 'scroll frame avg ≤ 18ms', when: 'perf', cat: 'P', ok: frame <= 18, detail: frame.toFixed(1) + 'ms' }];
}

(async () => {
  const browser = await chromium.launch(process.env.EVAL_CHROMIUM ? { executablePath: process.env.EVAL_CHROMIUM } : {});
  const cases = loadCases(casesDir); const all = [];
  for (const tc of cases) { const r = await runCase(browser, tc); r.forEach((x) => all.push(Object.assign({ id: tc.id }, x)));
    const pass = r.filter((x) => x.ok).length; console.log((pass === r.length ? 'PASS ' : 'FAIL ') + tc.id.padEnd(36) + pass + '/' + r.length);
    if (verbose || pass !== r.length) r.filter((x) => !x.ok).forEach((x) => console.log('       ✗ ' + x.name + ' [' + x.when + '] ' + (x.detail || ''))); }
  if (!args.includes('--no-perf')) (await perf(browser)).forEach((x) => { all.push(Object.assign({ id: 'perf' }, x)); console.log((x.ok ? 'PASS ' : 'FAIL ') + 'perf'.padEnd(36) + x.name + ' = ' + x.detail); });
  await browser.close();

  // Điểm chỉ đến từ ca fixed/sticky: một ca chỉ được tính đạt khi MỌI phép kiểm F/S của ca đó đạt.
  // An toàn (X) và hiệu năng (P) là cổng phạt: mỗi phép kiểm trượt trừ điểm.
  const rate = (cat) => { const ids = Array.from(new Set(all.filter((x) => x.cat === cat).map((x) => x.id)));
    if (!ids.length) return 1; return ids.filter((id) => all.filter((x) => x.id === id && x.cat === cat).every((x) => x.ok)).length / ids.length; };
  const failX = all.filter((x) => x.cat === 'X' && !x.ok).length, failP = all.filter((x) => x.cat === 'P' && !x.ok).length;
  const score = { F: 10 * rate('F'), S: 10 * rate('S'), failX, failP };
  const total = Math.max(0, 10 * (0.6 * rate('F') + 0.4 * rate('S')) - 0.4 * failX - 1.0 * failP);
  console.log('\nFixed ' + score.F.toFixed(2) + ' | Sticky ' + score.S.toFixed(2) + ' | safety fails ' + failX + ' | perf fails ' + failP);
  console.log('TOTAL ' + total.toFixed(2) + ' / 10   (integrity ' + integrity() + ', cases dir ' + path.basename(casesDir) + ')');

  const key = (x) => x.id + '::' + x.name + '::' + x.when;
  const state = {}; all.forEach((x) => { state[key(x)] = x.ok; });
  const basePath = path.join(__dirname, 'baseline.json');
  if (flag('write-baseline')) { fs.writeFileSync(basePath, JSON.stringify({ total, score, state }, null, 1)); console.log('baseline written'); }
  let regress = 0;
  if (fs.existsSync(basePath) && path.basename(casesDir) === 'cases') {
    const base = JSON.parse(fs.readFileSync(basePath, 'utf8'));
    Object.keys(base.state).forEach((k) => { if (base.state[k] && state[k] === false) { regress++; console.log('REGRESSION ' + k); } });
    console.log('baseline total ' + base.total.toFixed(2) + ' -> now ' + total.toFixed(2) + (regress ? '   (' + regress + ' regression(s))' : '   (no regressions)'));
  }
  if (opt('json')) fs.writeFileSync(opt('json'), JSON.stringify({ total, score, results: all }, null, 1));
  process.exit(regress ? 2 : 0);
})();
