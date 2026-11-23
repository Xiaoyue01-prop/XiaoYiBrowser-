// EVAL_CHROMIUM=/usr/bin/chromium node tools/check_edge_layout.cjs [motion|sticky|viewer|dump]
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { createRequire } = require('node:module');
const { chromium } = createRequire(require.resolve('../eval/package.json'))('playwright');

const template = fs.readFileSync(process.env.EDGE_SCRIPT ||
  path.join(__dirname, '../app/src/main/assets/edge_script.js'), 'utf8');
const scenarios = [];

function scenario(group, name, run) {
  scenarios.push({ group, name, run });
}

async function documentFor(page, css, content) {
  await page.setContent('<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">' +
    '<style>*{box-sizing:border-box}html,body{margin:0}body{font:15px sans-serif}' +
    css + '</style><body>' + content + '</body>');
}

async function enable(page, inset) {
  await page.evaluate(() => { window.EdgeBridge = new Proxy({}, { get: () => () => {} }); });
  await page.evaluate(template.replace('%INSET%', String(inset)).replace('%MODE%', 'true')
    .replace('%BOTTOM%', '0').replace('%SCROLL_BOTTOM%', '0')
    .replace('%COMPACT%', 'false').replace('%PILL_METRICS%', ''));
}

async function disable(page) {
  await page.evaluate(() => { window.__edgeMode = false; window.__edgeRun(); });
}

async function rect(page, selector) {
  return page.locator(selector).evaluate(el => {
    const { top, bottom, height } = el.getBoundingClientRect();
    return { top, bottom, height };
  });
}

function near(value, expected, label) {
  assert.ok(Math.abs(value - expected) <= 1, label + ' = ' + value + ' px, expected ' + expected + ' px');
}

async function inlineState(page) {
  return page.locator('[data-check]').evaluateAll(els => els.map(el => [
    el.style.cssText, ...el.getAttributeNames().filter(attr => attr.startsWith('data-edge'))
  ]));
}

async function fold(page, state, inline) {
  await page.evaluate(({ state, inline }) => {
    const bar = document.querySelector('[data-bar]');
    if (inline) {
      if (state) bar.style.setProperty('top', -bar.offsetHeight + 'px', 'important');
      else bar.style.removeProperty('top');
    } else {
      document.body.toggleAttribute('data-folded', state);
    }
  }, { state, inline });
  await page.waitForTimeout(430);
}

function motion({ name, position, tag, height, inset, hidden, inline, reverse, initiallyHidden }) {
  scenario('motion', name, async page => {
    const bar = '<' + tag + ' data-bar data-check role="banner"><button>Library</button></' + tag + '>';
    const filler = '<section style="height:2400px"></section>';
    await documentFor(page,
      '[data-bar]{position:' + position + ';inset-inline:0;top:0;height:' + height +
      'px;background:#357;transition:transform .1s,translate .1s,top .1s}' +
      'body[data-folded] [data-bar]{' + hidden + '}',
      '<div><div>' + (reverse ? filler + bar : bar + filler) + '</div></div>');
    await page.evaluate(() => window.scrollTo(0, 350));
    await fold(page, false, inline);
    const original = await inlineState(page);
    await fold(page, true, inline);
    const authoredHidden = await rect(page, '[data-bar]');
    assert.ok(authoredHidden.top < -1, 'fixture must retract above viewport');
    if (!initiallyHidden) await fold(page, false, inline);
    await enable(page, inset);
    await page.waitForTimeout(400);
    if (initiallyHidden) {
      near((await rect(page, '[data-bar]')).top, authoredHidden.top, 'initially hidden');
      await fold(page, false, inline);
    }
    near((await rect(page, '[data-bar]')).top, inset, 'visible');
    await fold(page, true, inline);
    near((await rect(page, '[data-bar]')).top, authoredHidden.top, 'retracted');
    await fold(page, false, inline);
    near((await rect(page, '[data-bar]')).top, inset, 'revealed');
    await disable(page);
    assert.deepEqual(await inlineState(page), original);
  });
}

motion({ name: 'fixed footer tag, translated, later in DOM', position: 'fixed', tag: 'footer',
  height: 73, inset: 31, hidden: 'transform:translate3d(0,-100%,0)', reverse: true });
motion({ name: 'fixed toolbar, partial individual translate', position: 'fixed', tag: 'div',
  height: 81, inset: 41, hidden: 'translate:0 -64%' });
motion({ name: 'fixed CSS top changed on ancestor, starts hidden', position: 'fixed', tag: 'section',
  height: 63, inset: 27, hidden: 'top:-63px', initiallyHidden: true });
motion({ name: 'fixed inline negative top with priority', position: 'fixed', tag: 'menu',
  height: 68, inset: 37, hidden: '', inline: true });
motion({ name: 'nested wrapper with sticky transformed toolbar', position: 'sticky', tag: 'nav',
  height: 67, inset: 35, hidden: 'transform:translateY(-100%)' });
motion({ name: 'sticky toolbar with animated CSS top', position: 'sticky', tag: 'div',
  height: 79, inset: 29, hidden: 'top:-79px' });

function companion({ name, height, inset, position, tag, rowTag, reverse, zero, alreadyScrolled, late, resize, hide }) {
  scenario('sticky', name, async page => {
    const bar = '<' + tag + ' data-bar data-check role="banner"><button>Catalog</button></' + tag + '>';
    const gap = position === 'fixed' ? '<div class="gap"></div>' : '';
    const row = '<' + rowTag + ' data-row data-check><button>Browse</button></' + rowTag + '>';
    const list = '<div class="list">' + (late ? '<div style="height:380px"></div>' : '') +
      row + '<div style="height:2700px"></div></div>';
    await documentFor(page,
      'html{--mast:' + height + 'px}[data-bar]{position:' + position + ';top:0;inset-inline:0;' +
      'height:var(--mast);z-index:5;background:#637}[data-row]{display:block;position:sticky;' +
      'top:' + (zero ? '0' : 'var(--mast)') + ';height:27px;background:#ddd}.gap{height:var(--mast)}' +
      'body[data-folded] [data-bar]{transform:translateY(-100%)}',
      '<div><div>' + (reverse ? gap + list + bar : bar + gap + list) + '</div></div>');
    const original = await inlineState(page);
    if (alreadyScrolled) await page.evaluate(() => window.scrollTo(0, 630));
    await enable(page, inset);
    async function joined(label, statusInset) {
      const upper = await rect(page, '[data-bar]');
      near(upper.top, statusInset, label + ' header');
      near((await rect(page, '[data-row]')).top, upper.bottom, label + ' row');
    }
    await page.waitForTimeout(380);
    if (!late) await joined('initial', inset);
    else await page.waitForTimeout(1810); // Let startup retries finish before the offscreen row arrives.
    await page.evaluate(() => window.scrollTo(0, 830));
    await page.waitForTimeout(370);
    await joined('pinned', inset);
    if (resize) {
      await page.evaluate(h => document.documentElement.style.setProperty('--mast', h + 'px'), height + 22);
      await page.waitForTimeout(380);
      await joined('resized', inset);
    }
    if (hide) {
      await fold(page, true, false);
      near((await rect(page, '[data-row]')).top, height, 'page threshold while header hidden');
      await fold(page, false, false);
      await joined('header returns', inset);
    }
    await page.evaluate(n => { window.__edgeInset = n; window.__edgeRun(); window.__edgeRun(); }, inset + 8);
    await joined('new inset', inset + 8);
    await disable(page);
    assert.deepEqual(await inlineState(page), original);
  });
}

companion({ name: 'thin chips after 37px fixed bar', position: 'fixed', tag: 'div', rowTag: 'section', height: 37, inset: 28, resize: true });
companion({ name: 'fixed landmark later in DOM, row already pinned', position: 'fixed', tag: 'nav', rowTag: 'div', height: 89, inset: 39, reverse: true, alreadyScrolled: true });
companion({ name: 'remember natural contact of a zero-top breadcrumb', position: 'fixed', tag: 'section', rowTag: 'nav', height: 71, inset: 35, reverse: true, zero: true });
companion({ name: 'two sticky bars in different wrappers', position: 'sticky', tag: 'div', rowTag: 'section', height: 67, inset: 33, resize: true });
companion({ name: 'offscreen filter reaches header after startup', position: 'fixed', tag: 'div', rowTag: 'aside', height: 83, inset: 29, late: true });
companion({ name: 'anchor hides and returns while filter is pinned', position: 'fixed', tag: 'section', rowTag: 'div', height: 79, inset: 35, hide: true });

function layers(name, local) {
  scenario('sticky', name, async page => {
    const layers = '<article><div data-outer data-check><div class="label"><button>Group</button></div>' +
      '<section><div data-inner data-check><button>Subgroup</button></div></section></div>' +
      '<div data-tail data-check><button>Actions</button></div><div style="height:2400px"></div></article>';
    const isolation = '<div class="separate"><div data-other data-check>Other pane</div><p style="height:900px"></p></div>';
    await documentFor(page, 'article{min-height:2600px}.label{height:37px}[data-outer],[data-inner],[data-tail],[data-other]{position:sticky}' +
      '[data-outer],[data-other]{top:0}[data-inner]{top:37px;height:26px}[data-tail]{top:63px;height:21px}' +
      '.pane{position:absolute;top:9px;left:0;right:0;border-top:4px solid;height:300px;overflow:auto}' +
      '.separate{position:absolute;top:370px;right:0;width:120px;height:110px;overflow:auto}',
      local ? '<div class="pane">' + layers + '</div>' + isolation : layers);
    const original = await inlineState(page);
    await enable(page, 41);
    await page.evaluate(local => {
      if (local) {
        document.querySelector('.pane').scrollTop = 600;
        document.querySelector('.separate').scrollTop = 190;
      } else window.scrollTo(0, 600);
    }, local);
    await page.waitForTimeout(450);
    for (const inset of [41, 32]) {
      if (inset !== 41) await page.evaluate(n => { window.__edgeInset = n; window.__edgeRun(); }, inset);
      near((await rect(page, '[data-outer]')).top, inset, 'outer');
      near((await rect(page, '[data-inner]')).top, inset + 37, 'inner');
      near((await rect(page, '[data-tail]')).top, inset + 63, 'next layer');
      for (const selector of ['.label button', '[data-inner] button', '[data-tail] button']) {
        assert.ok((await rect(page, selector)).top >= inset - 1, 'obscured control in ' + selector);
      }
    }
    if (local) near((await rect(page, '[data-other]')).top, 370, 'independent scrollport');
    await disable(page);
    assert.deepEqual(await inlineState(page), original);
  });
}

layers('nested sticky title, inner control and trailing sibling', false);
layers('three layers in bordered scrollport with independent pane', true);
scenario('sticky', 'three sibling levels in CSS order, reversed DOM', async page => {
  await documentFor(page, 'article{display:flex;flex-direction:column;height:2800px}article>*{flex:none;position:sticky}' +
    '[data-first]{order:1;top:0;height:34px}[data-second]{order:2;top:34px;height:23px}' +
    '[data-third]{order:3;top:57px;height:39px}',
    '<article><div data-third data-check>More</div><section data-second data-check>Options</section><div data-first data-check>Summary</div></article>');
  const original = await inlineState(page);
  await enable(page, 31);
  await page.evaluate(() => window.scrollTo(0, 430));
  await page.waitForTimeout(450);
  for (const [sel, top] of [['[data-first]', 31], ['[data-second]', 65], ['[data-third]', 88]]) {
    near((await rect(page, sel)).top, top, 'ordered layer');
  }
  await disable(page);
  assert.deepEqual(await inlineState(page), original);
});

function viewer(name, options = {}) {
  scenario('viewer', name, async page => {
    const inset = options.inset || 38;
    const top = options.top || 6;
    const rootTag = options.native ? 'dialog' : 'section';
    const action = options.row ? '<div class="actions" data-check><button data-close>Close</button><a href="#options">More</a></div>' :
      '<button data-close data-check style="margin-top:2px!important"><span>Close</span></button>' +
      '<span role="button" tabindex="0" data-other data-check>More</span>';
    const sheet = '<' + rootTag + ' hidden ' + (options.role ? 'role="dialog"' : '') +
      ' class="viewer" data-check><div class="stage"><div class="photo"></div>' + action +
      '<button class="safe" data-check>Save</button></div></' + rootTag + '>';
    const background = options.header ? '<header data-check><button>Gallery</button></header>' : '';
    await documentFor(page,
      'header{position:fixed;inset:0 0 auto;height:53px;background:white}' +
      '.viewer{position:' + (options.absolute ? 'absolute' : 'fixed') + ';inset:0;z-index:7;' +
      'margin:0;border:0;max-width:none;max-height:none;width:100%;height:100%;padding:0;background:white}' +
      '.stage{position:absolute;inset:0}.photo{height:62vh;background:#a42}' +
      'button,[role=button],a{display:inline-grid;place-items:center;height:39px;min-width:36px}' +
      '.actions,[data-close],[data-other]{position:' + (options.fixedControls ? 'fixed' : 'absolute') +
      ';top:' + top + 'px;right:6px}.actions [data-close]{position:static}' +
      '[data-other]{right:65px}.safe{position:absolute;top:140px}' +
      '.under{position:fixed;inset:2px auto auto 2px}.long{height:200vh}',
      (options.reverse ? sheet + background : background + sheet) +
      '<button class="under" data-check>Outside</button><main class="long"></main>');
    await enable(page, inset);
    const outside = await rect(page, '.under');
    await page.locator('.viewer').evaluate((panel, native) => {
      panel.hidden = false;
      if (native) panel.showModal();
      window.hits = 0;
      panel.addEventListener('click', e => { if (e.target.closest('[data-close]')) window.hits++; });
    }, !!options.native);
    await page.waitForTimeout(430);
    const closeTop = (await rect(page, '[data-close]')).top;
    assert.ok(closeTop >= inset - 0.5 && closeTop <= inset + top + 3,
      'button top ' + closeTop + ' px, safe edge ' + inset + ' px');
    if (!options.row) assert.ok((await rect(page, '[data-other]')).top >= inset - 0.5);
    near((await rect(page, '.photo')).top, 0, 'photo');
    near((await rect(page, '.safe')).top, 140, 'already safe action');
    near((await rect(page, '.under')).top, outside.top, 'unrelated floating action');
    await page.locator('[data-close]').tap({timeout:1800});
    assert.equal(await page.evaluate(() => window.hits), 1);
    await page.evaluate(n => { window.__edgeInset = n; window.__edgeRun(); window.__edgeRun(); }, inset + 11);
    const resized = (await rect(page, '[data-close]')).top;
    assert.ok(resized >= inset + 10.5 && resized <= inset + 11 + top + 3, 'inset update ' + resized);
    await page.locator('.viewer').evaluate((panel, native) => {
      if (native) panel.close();
      panel.hidden = true;
    }, !!options.native);
    await page.waitForTimeout(360);
    if (options.header) near((await rect(page, 'header')).top, inset + 11, 'restored background header');
    const state = await page.locator('[data-close]').evaluate(e => [e.style.marginTop, e.style.getPropertyPriority('margin-top')]);
    assert.deepEqual(state, options.row ? ['', ''] : ['2px', 'important']);
    assert.deepEqual(await page.locator('.viewer').evaluate(e => e.style.cssText), '');
  });
}
viewer('absolute controls in a full-bleed image stage');
viewer('fixed actions, header behind viewer, panel first in DOM', {fixedControls:true,header:true,reverse:true,inset:45,top:11});
viewer('one anchored wrapper containing two actions', {row:true,inset:27,top:3});
viewer('absolute modal over a fixed background header', {absolute:true,role:true,header:true,top:12});
viewer('top-layer dialog above the page header', {native:true,row:true,header:true,inset:31});

scenario('viewer', 'ordinary image card retains its authored buttons', async page => {
  await documentFor(page, '.card{position:relative;height:80vh;background:#c65}' +
    'button{position:absolute;right:8px;top:3px;height:40px}',
    '<main class="card"><button data-check>Bookmark</button></main>');
  const original = await inlineState(page);
  await enable(page, 34);
  assert.deepEqual(await inlineState(page), original);
});

scenario('viewer', 'viewport-sized absolute panel on a scroll-locked page', async page => {
  await documentFor(page, 'body{overflow:hidden}section{position:absolute;inset:0;background:#fafafa}' +
    'button{position:absolute;top:9px;right:3px;height:42px}', '<section><button>Dismiss</button></section>');
  await enable(page, 36);
  near((await rect(page, 'button')).top, 36, 'scroll-locked panel action');
  await disable(page);
  near((await rect(page, 'button')).top, 9, 'original anchor');
});

scenario('viewer', 'replacement in open shadow root retains the latest author style', async page => {
  await documentFor(page, '.panel{position:fixed;inset:0;background:#ddd}', '<div class="panel"><x-tools></x-tools></div>');
  await page.locator('x-tools').evaluate(host => {
    host.attachShadow({mode:'open'}).innerHTML = '<style>button{position:fixed;left:3px;top:4px;height:41px}</style><button>Initial</button>';
  });
  await enable(page, 32);
  const button = page.locator('x-tools button');
  near((await rect(page, 'x-tools button')).top, 32, 'shadow action');
  await button.evaluate(el => {
    window.oldAction = el;
    const replacement = document.createElement('button');
    replacement.textContent = 'New';
    replacement.style.setProperty('margin-top', '7px', 'important');
    el.replaceWith(replacement);
  });
  await page.waitForTimeout(410);
  near((await rect(page, 'x-tools button')).top, 32, 'new action');
  assert.equal(await page.evaluate(() => window.oldAction.style.marginTop), '');
  await button.evaluate(el => el.style.setProperty('margin-top', '13px', 'important'));
  await page.waitForTimeout(400);
  near((await rect(page, 'x-tools button')).top, 32, 'after author update');
  await disable(page);
  assert.deepEqual(await button.evaluate(el => [el.style.marginTop, el.style.getPropertyPriority('margin-top')]), ['13px','important']);
});

scenario('viewer', 'nested absolute button remains tappable inside a clipping toolbar', async page => {
  await documentFor(page, 'section{position:fixed;inset:0;background:#aaa}.clip{position:absolute;top:3px;right:5px;' +
    'overflow:hidden;height:38px;width:90px}.clip button{position:absolute;top:1px;right:2px;height:34px}',
    '<section><div class="clip"><button>Close</button></div></section>');
  await enable(page, 43);
  const top = (await rect(page, 'button')).top;
  assert.ok(top >= 42.5, 'button beneath status bar ' + top);
  assert.equal(await page.locator('button').evaluate(el => {
    const r = el.getBoundingClientRect();
    return document.elementFromPoint(r.x + r.width / 2, r.y + r.height / 2) === el;
  }), true, 'clipping toolbar must move with its action');
  await disable(page);
  near((await rect(page, 'button')).top, 4, 'restored nested button');
});

scenario('viewer', 'sticky labels in a fixed dialog keep their scroll threshold', async page => {
  await documentFor(page, 'section{position:fixed;inset:0;background:#aaa;overflow:auto}article{height:2200px}' +
    'header{position:fixed;inset:0 0 auto;height:54px;background:#fff}h3{position:sticky;top:0;margin:0;height:36px}',
    '<header>Behind</header><section role="dialog"><article><h3>Photos</h3></article></section>');
  await enable(page, 33);
  await page.locator('section').evaluate(el => { el.scrollTop = 350; });
  await page.waitForTimeout(450);
  near((await rect(page, 'h3')).top, 33, 'scrolled dialog title');
  await disable(page);
  near((await rect(page, 'h3')).top, 0, 'original sticky threshold');
});

scenario('dump', 'read the paint stack and composed ancestry across the entire debug band', async page => {
  await documentFor(page, '.under,x-tools{position:fixed;inset:0 auto auto 0;width:100%;height:115px}' +
    '.under{background:#c34}x-tools{z-index:2}', '<aside class="under"></aside><x-tools></x-tools>');
  await page.locator('x-tools').evaluate(host => {
    host.attachShadow({mode:'open'}).innerHTML = '<button style="width:100%;height:115px">Action</button>';
  });
  await page.evaluate(() => { window.__edgeMode = true; window.__edgeInset = 37; });
  const capture = fs.readFileSync(process.env.EDGE_DUMP_SCRIPT ||
    path.join(__dirname, '../app/src/debug/assets/top_band_dump.js'), 'utf8');
  const before = await page.content();
  const output = await page.evaluate(capture);
  const lines = output.split('\n');
  const keyFor = name => {
    const match = lines.find(l => new RegExp('^\\d+ ' + name + '(?: |$)').test(l));
    assert.ok(match, 'missing ' + name);
    return match.split(' ')[0];
  };
  const action = keyFor('button'), host = keyFor('x-tools'), under = keyFor('aside\\.under');
  for (const y of [0, 37, 110]) {
    const line = lines.find(l => l.startsWith('x=0 y=' + y + ' '));
    assert.ok(line && line.includes('chain ' + action + ' > ' + host + ' > '));
    assert.ok(line.split(' | stack ')[1].split(' ').includes(under));
  }
  assert.ok(lines.some(l => l.includes('dpr=') && l.endsWith('inset=37')));
  assert.equal(await page.content(), before);
});

async function main() {
  const group = process.argv[2];
  const selected = scenarios.filter(s => !group || s.group === group);
  assert.ok(selected.length, 'Unknown scenario group: ' + group);
  const browser = await chromium.launch(process.env.EVAL_CHROMIUM ? { executablePath: process.env.EVAL_CHROMIUM } : {});
  let passed = 0;
  try {
    for (const s of selected) {
      const page = await browser.newPage({ viewport: { width: 417, height: 851 }, isMobile: true, hasTouch: true });
      const errors = [];
      page.on('pageerror', error => errors.push(error.message));
      try {
        await s.run(page);
        assert.deepEqual(errors, []);
        console.log('PASS', s.name);
        passed++;
      } catch (error) {
        console.error('FAIL', s.name, error.message);
      } finally {
        await page.close();
      }
    }
  } finally {
    await browser.close();
  }
  console.log(passed + '/' + selected.length + ' Chromium scenarios passed');
  if (passed !== selected.length) process.exitCode = 1;
}

main().catch(error => { console.error(error); process.exitCode = 1; });
