// EVAL_CHROMIUM=/usr/bin/chromium node tools/check_edge_layout.cjs [motion|sticky]
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
