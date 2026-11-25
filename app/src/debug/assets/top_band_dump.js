(function () {
  const viewport = window.visualViewport;
  const width = viewport ? viewport.width : innerWidth;
  const height = viewport ? viewport.height : innerHeight;
  const scale = viewport ? viewport.scale : 1;
  const inset = Number(window.__edgeInset);
  const reserve = Number.isFinite(inset) ? inset / scale : null;
  const nodes = new Map();
  const descriptions = [];
  const points = [];
  const round = Math.round;

  function parent(el) {
    return el.assignedSlot || el.parentElement || el.getRootNode().host;
  }

  function key(el) {
    if (nodes.has(el)) return nodes.get(el);
    if (nodes.size >= 256) return '…';
    const id = nodes.size + 1;
    nodes.set(el, id);
    const cs = getComputedStyle(el);
    const r = el.getBoundingClientRect();
    const name = el.localName + (el.id ? '#' + el.id.slice(0, 64) : '') +
      Array.from(el.classList).slice(0, 3).map(c => '.' + c.slice(0, 48)).join('');
    const role = el.getAttribute('role');
    descriptions.push(id + ' ' + name + (role ? ' role=' + role.slice(0, 32) : '') +
      ' | pos=' + cs.position + ' top=' + cs.top + ' transform=' + cs.transform +
      ' translate=' + cs.translate + ' overflow-y=' + cs.overflowY + ' z=' + cs.zIndex +
      ' pointer-events=' + cs.pointerEvents + ' | x=' + round(r.left) + '..' + round(r.right) +
      ' y=' + round(r.top) + '..' + round(r.bottom));
    return id;
  }

  const cols = Math.max(1, Math.min(40, Math.ceil(width / 16)));
  const xs = Array.from({length: cols + 1}, (_, i) => Math.round(i * (Math.floor(width) - 1) / cols));
  const ys = [0, 8, 55, 110, round(110 / (devicePixelRatio * scale))];
  for (let y = 10; y < 110; y += 10) ys.push(y);
  if (reserve !== null && reserve > 0 && reserve <= 110) ys.push(Math.floor(reserve));
  for (const y of [...new Set(ys)].sort((a, b) => a - b)) {
    if (y < 0 || y > 110 || y >= height) continue;
    const runs = [];
    for (const x of xs) {
      const stack = document.elementsFromPoint(x, y).slice(0, 6);
      let hit = stack[0];
      while (hit && hit.shadowRoot) {
        const inner = hit.shadowRoot.elementFromPoint(x, y);
        if (!inner || inner === hit || inner.getRootNode() !== hit.shadowRoot) break;
        hit = inner;
      }
      const chain = [];
      for (let el = hit; el && chain.length < 24; el = parent(el)) chain.push(key(el));
      const path = ' | chain ' + chain.join(' > ') + ' | stack ' + stack.map(key).join(' ');
      const previous = runs[runs.length - 1];
      if (previous && previous.path === path) previous.last = x;
      else runs.push({first: x, last: x, path});
    }
    for (const run of runs) {
      points.push('x=' + run.first + ' y=' + y + (run.last > run.first ? ' … x=' + run.last : '') + run.path);
    }
  }
  return ['url ' + location.href, 'CSS px | layout=' + innerWidth + 'x' + innerHeight +
    ' visual=' + round(width) + 'x' + round(height) + ' scale=' + scale +
    ' dpr=' + devicePixelRatio + ' scrollY=' + round(scrollY) + ' edge=' + window.__edgeMode +
    ' inset=' + (reserve === null ? '?' : reserve), ...points, '', ...descriptions].join('\n');
})()
