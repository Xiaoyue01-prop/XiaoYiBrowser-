// Helper dùng chung cho các ca test: tạo trang, các phép kiểm (check) và đo đạc.
const INSET = 50;

function page(css, body, opts) {
  opts = opts || {};
  const meta = opts.noMeta ? '' : '<meta name="viewport" content="width=device-width, initial-scale=1">';
  return '<!doctype html><html><head>' + meta + '<style>html,body{margin:0}body{height:3000px;font:16px sans-serif}' +
    (css || '') + '</style></head><body>' + body + '</body></html>';
}

// Mỗi check: { name, when: 'load' | 'scrolled' | 'restored', cat?, run(ctx) -> {ok, detail} }
const near = (a, b, tol) => Math.abs(a - b) <= tol;

const topIs = (sel, want, tol, when) => ({
  name: 'top(' + sel + ')≈' + want, when: when || 'load',
  run: async (c) => { const m = await c.measure(sel); return { ok: !!m && near(m.top, want, tol == null ? 1 : tol), detail: m && m.top.toFixed(1) }; }
});
const colorIs = (rgb, tol) => ({
  name: 'color≈' + rgb.join(','), when: 'load',
  run: async (c) => { const col = await c.lastColor(); return { ok: !!col && col.every((v, i) => near(v, rgb[i], tol == null ? 8 : tol)), detail: col ? col.join(',') : 'none' }; }
});
const noBand = (when) => ({
  name: 'no content under status bar', when: when || 'scrolled',
  run: async (c) => { const v = await c.band(); return { ok: v.length === 0, detail: v.slice(0, 3).join(' | ') }; }
});
const adjacent = (lower, upper, when) => ({
  name: lower + ' sits right under ' + upper, when: when || 'load',
  run: async (c) => { const a = await c.measure(lower), b = await c.measure(upper);
    return { ok: !!a && !!b && a.top >= b.bottom - 1.5 && a.top <= b.bottom + 2, detail: a && b ? a.top.toFixed(1) + ' vs ' + b.bottom.toFixed(1) : 'missing' }; }
});
const liftIs = (sel, want) => ({
  name: 'bottom gap(' + sel + ')≈' + want, when: 'load', cat: 'X',
  run: async (c) => { const m = await c.measure(sel); return { ok: !!m && near(m.gapBottom, want, 1.5), detail: m && m.gapBottom.toFixed(1) }; }
});
const unchangedRect = (sel, when) => ({
  name: sel + ' untouched', when: when || 'scrolled', cat: 'X',
  run: async (c) => { const m = await c.measure(sel), i = c.initial[sel]; return { ok: !!m && !!i && near(m.top, i.top, 0.6) && near(m.left, i.left, 0.6), detail: m && i ? m.top.toFixed(1) + ' vs ' + i.top.toFixed(1) : 'missing' }; }
});

module.exports = { INSET, page, topIs, colorIs, noBand, adjacent, liftIs, unchangedRect, near };
