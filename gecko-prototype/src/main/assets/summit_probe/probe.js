// Summit Probe: content script.
// Detects top navigation/header layers, offsets only those layers below the Android
// status bar, restores stale offsets after SPA navigation, and reports diagnostics.
(() => {
  'use strict';
  if (window.__summitProbe) return;
  window.__summitProbe = true;

  const MIN_GAP_MS = 1200;
  const MAX_NODES = 6000;
  const MAX_HEADER_TOP = 240;
  const MAX_FLOW_HEADER_DOC_TOP = 260;
  const timerState = { id: null, due: 0, pending: false };
  let lastScan = 0;
  let lastKey = '';
  let scanStarted = false;
  const inFlight = new Set();

  // Remember the exact inline style before Summit modifies an element. A Map is used
  // intentionally so that SPA route changes can restore nodes that stop being headers.
  const activeShifts = new Map();
  // On YouTube watch pages the player may sit behind the fixed masthead. When Summit
  // moves the masthead below the status bar, the player's own top-row controls can end
  // up underneath it. Keep a separate, reversible offset for that player control row.
  const activePlayerOverlayShifts = new Map();
  // Ordinary document-flow headers are not fixed/sticky. When one begins underneath
  // the Android status bar, add a reversible margin to the page's top-level flow root.
  // The header remains in normal flow and continues to scroll with the document.
  const activeFlowOffsets = new Map();
  let edgeModeEnabled = true;
  let headerScrollEnabled = false;
  let lastHeaderScrollMode = null;
  const PLAYER_TOP_OVERLAY_SELECTOR =
    '#movie_player .ytp-chrome-top, .html5-video-player .ytp-chrome-top, ' +
    '.ytm-player-control-overlay-top, .ytm-player-controls-top';

  const EXCLUDED_TAGS = new Set([
    'ytd-watch-flexy',
    'ytd-player',
    'ytd-watch-metadata',
    'ytd-video-primary-info-renderer',
    'ytd-video-secondary-info-renderer',
    'ytd-comments',
    'ytd-comments-entry-point-header-renderer',
    'ytd-watch-next-secondary-results-renderer',
    'ytm-watch',
    'ytm-player',
    'ytm-slim-video-metadata-section-renderer'
  ]);

  const HEADER_TAGS = new Set([
    'header',
    'nav',
    'ytd-masthead',
    'ytd-feed-filter-chip-bar-renderer',
    'ytm-mobile-topbar-renderer',
    'ytm-pivot-bar-renderer'
  ]);

  const HEADER_HINT_RE = /(^|[\s_-])(header|masthead|topbar|top-bar|navbar|nav-bar|navigation|toolbar|appbar|app-bar|filter-chip|chip-bar|pivot-bar|tab-bar)([\s_-]|$)/i;
  // YouTube watch-page player overlays are not navigation headers. These classes can be
  // fixed/sticky and sit near the top of the viewport, so exclude them explicitly.
  const EXCLUDED_CONTEXT_RE = /(watch-metadata|video-primary-info|video-secondary-info|video-player|comments-entry|watch-next|movie_player|player-container|html5-video|ytp-chrome|ytp-title|ytp-gradient|ytp-tooltip|ytp-progress|ytp-controls)/i;

  let insetEl = null;
  function readInset() {
    if (!edgeModeEnabled) return 0;
    try {
      if (!insetEl) {
        insetEl = document.createElement('div');
        insetEl.style.cssText =
          'position:absolute;left:-9999px;top:0;width:1px;height:1px;visibility:hidden;' +
          'pointer-events:none;padding-top:env(safe-area-inset-top,0px)';
      }
      if (!insetEl.isConnected) (document.body || document.documentElement).appendChild(insetEl);
      return parseFloat(getComputedStyle(insetEl).paddingTop) || 0;
    } catch (e) {
      return 0;
    }
  }

  function alphaOf(css) {
    if (!css || css === 'transparent') return 0;
    const m = css.match(/rgba?\(([^)]+)\)/);
    if (!m) return 0;
    const parts = m[1].split(/[\s,\/]+/).filter(Boolean);
    return parts.length > 3 ? parseFloat(parts[3]) : 1;
  }

  function solidBackground(el) {
    const own = getComputedStyle(el).backgroundColor;
    if (alphaOf(own) > 0.5) return own;
    let child = el.firstElementChild;
    for (let i = 0; i < 3 && child; i++) {
      const c = getComputedStyle(child).backgroundColor;
      if (alphaOf(c) > 0.5) return c;
      child = child.firstElementChild;
    }
    let p = el.parentElement;
    while (p) {
      const c = getComputedStyle(p).backgroundColor;
      if (alphaOf(c) > 0.5) return c;
      p = p.parentElement;
    }
    return '';
  }

  function pageBackgroundColor() {
    const body = document.body;
    const html = document.documentElement;
    const candidates = [body, html, body && body.firstElementChild];
    for (const el of candidates) {
      if (!el) continue;
      const own = getComputedStyle(el).backgroundColor;
      if (alphaOf(own) > 0.5) return own;
      const nested = solidBackground(el);
      if (nested && alphaOf(nested) > 0.5) return nested;
    }
    return '';
  }

  function requestPageSettings() {
    try {
      if (typeof browser === 'undefined' || !browser.runtime ||
          typeof browser.runtime.sendNativeMessage !== 'function') return;
      browser.runtime.sendNativeMessage('browser', { type: 'settingsQuery' }).then(settings => {
        if (!settings) return;
        const nextEdge = settings.edgeMode !== false;
        const nextHeader = settings.headerScroll === true;
        const changed = nextEdge !== edgeModeEnabled || nextHeader !== headerScrollEnabled;
        edgeModeEnabled = nextEdge;
        headerScrollEnabled = nextHeader;
        if (changed) schedule(100);
      }).catch(() => {});
    } catch (_) {}
  }

  const VOLATILE = new Set(['scanMs', 'timestamp', 'nodes', 'excluded']);
  const HEARTBEAT_MS = 4000;
  let lastSentAt = 0;
  function send(msg) {
    const key = JSON.stringify(msg, (k, v) => (VOLATILE.has(k) ? undefined : v));
    if (inFlight.has(key)) return;
    if (key === lastKey && Date.now() - lastSentAt < HEARTBEAT_MS) return;

    if (typeof browser === 'undefined' ||
        !browser.runtime ||
        typeof browser.runtime.sendNativeMessage !== 'function') {
      window.__summitProbeStatus = 'native-messaging-api-unavailable';
      console.error('[Summit Probe] browser.runtime.sendNativeMessage is unavailable');
      return;
    }

    inFlight.add(key);
    try {
      Promise.resolve(browser.runtime.sendNativeMessage('browser', msg)).then(
        () => {
          lastKey = key;
          lastSentAt = Date.now();
          inFlight.delete(key);
          window.__summitProbeStatus = 'native-message-sent';
        },
        error => {
          inFlight.delete(key);
          window.__summitProbeStatus = 'native-message-error';
          console.error('[Summit Probe] sendNativeMessage failed:', error);
        }
      );
    } catch (error) {
      inFlight.delete(key);
      window.__summitProbeStatus = 'native-message-error';
      console.error('[Summit Probe] sendNativeMessage threw:', error);
    }
  }

  function nodeTokens(el) {
    const tag = (el.tagName || '').toLowerCase();
    const id = typeof el.id === 'string' ? el.id : '';
    const classes = typeof el.className === 'string' ? el.className :
      (el.className && typeof el.className.baseVal === 'string' ? el.className.baseVal : '');
    const role = el.getAttribute ? (el.getAttribute('role') || '') : '';
    const aria = el.getAttribute ? (el.getAttribute('aria-label') || '') : '';
    return { tag, id, classes, role, aria };
  }

  function isExcludedContent(el) {
    let node = el;
    for (let depth = 0; node && depth < 12; depth++, node = node.parentElement) {
      const t = nodeTokens(node);
      const identity = `${t.tag} ${t.id} ${t.classes}`;
      if (EXCLUDED_TAGS.has(t.tag) || EXCLUDED_CONTEXT_RE.test(identity)) return true;
    }
    return false;
  }

  function containsExcludedContent(el) {
    try {
      // Reject a candidate if it is itself, or wraps, any player/watch-content region.
      // This guard must run even when an ancestor happens to have a 'header'/'navigation' hint.
      const selector =
        'ytd-watch-flexy, ytd-player, ytd-watch-metadata, ytd-video-primary-info-renderer, ' +
        'ytd-video-secondary-info-renderer, ytd-comments, ytd-watch-next-secondary-results-renderer, ' +
        'ytm-watch, ytm-player, ytm-slim-video-metadata-section-renderer, ' +
        '#movie_player, .html5-video-player, .html5-video-container, .ytp-chrome-top, ' +
        '.ytp-title, .ytp-gradient-top, .ytp-chrome-controls, video';
      return el.matches(selector) || !!el.querySelector(selector);
    } catch (e) {
      return false;
    }
  }

  function findHeaderHint(el) {
    let node = el;
    for (let depth = 0; node && depth < 8; depth++, node = node.parentElement) {
      const t = nodeTokens(node);
      if (HEADER_TAGS.has(t.tag) || t.role === 'banner' || t.role === 'navigation') {
        return t.tag || t.role;
      }
      const identity = `${t.tag} ${t.id} ${t.classes} ${t.aria}`;
      const match = identity.match(HEADER_HINT_RE);
      if (match) return (match[2] || t.tag).toLowerCase();
    }
    return '';
  }

  function findHeaderHintDepth(el) {
    let node = el;
    for (let depth = 0; node && depth < 8; depth++, node = node.parentElement) {
      const t = nodeTokens(node);
      if (HEADER_TAGS.has(t.tag) || t.role === 'banner' || t.role === 'navigation') return depth;
      const identity = `${t.tag} ${t.id} ${t.classes} ${t.aria}`;
      if (HEADER_HINT_RE.test(identity)) return depth;
    }
    return -1;
  }

  function selfHeaderHint(el) {
    const t = nodeTokens(el);
    if (HEADER_TAGS.has(t.tag)) return t.tag;
    if (t.role === 'banner' || t.role === 'navigation') return t.role;
    const identity = `${t.tag} ${t.id} ${t.classes} ${t.aria}`;
    const match = identity.match(HEADER_HINT_RE);
    return match ? (match[2] || t.tag).toLowerCase() : '';
  }

  function topLevelBodyChild(el, body) {
    let node = el;
    while (node && node.parentElement && node.parentElement !== body) {
      node = node.parentElement;
    }
    return node && node.parentElement === body ? node : null;
  }

  function restoreFlowOffset(el, state) {
    try {
      if (state.inlineMarginTop) {
        el.style.setProperty('margin-top', state.inlineMarginTop, state.inlinePriority || '');
      } else {
        el.style.removeProperty('margin-top');
      }
    } catch (e) {
      console.warn('[Summit Probe] could not restore flow offset:', e);
    }
    activeFlowOffsets.delete(el);
  }

  function findFallbackFlowRoot(body, vw, inset, scrollY) {
    // If a page uses a generic app shell instead of a semantic <header>/<nav>,
    // use only the first visible, full-width normal-flow body child. This is a
    // conservative fallback, not a search through arbitrary content sections.
    for (const el of Array.from(body.children || [])) {
      const tag = (el.tagName || '').toLowerCase();
      if (['script', 'style', 'link', 'meta', 'template', 'noscript'].includes(tag)) continue;
      const cs = getComputedStyle(el);
      if ((cs.position !== 'static' && cs.position !== 'relative') ||
          cs.display === 'none' || cs.visibility === 'hidden') continue;
      if (isExcludedContent(el) || containsExcludedContent(el)) continue;
      const rect = el.getBoundingClientRect();
      if (rect.width < vw * 0.72 || rect.height < 24) continue;
      const state = activeFlowOffsets.get(el);
      const baseDocTop = rect.top + scrollY - (state ? state.appliedInset : 0);
      if (baseDocTop < -24 || baseDocTop > MAX_FLOW_HEADER_DOC_TOP ||
          baseDocTop >= inset - 2) continue;
      return {
        root: el,
        anchor: el,
        selector: describeElement(el),
        baseDocTop,
        fallback: true
      };
    }
    return null;
  }

  function applyFlowOffset(target, inset, scrollY) {
    const activeRoot = target ? target.root : null;
    for (const [el, state] of Array.from(activeFlowOffsets.entries())) {
      if (el !== activeRoot || inset <= 0) restoreFlowOffset(el, state);
    }
    if (!target || inset <= 0) {
      return { shifted: 0, selector: 'none', offset: 0, fallback: false };
    }

    const root = target.root;
    let state = activeFlowOffsets.get(root);
    const currentAnchorTop = target.anchor.getBoundingClientRect().top + scrollY;
    const baseAnchorTop = currentAnchorTop - (state ? state.appliedInset : 0);
    const needed = Math.max(0, inset - baseAnchorTop);
    if (needed <= 1) {
      if (state) restoreFlowOffset(root, state);
      return { shifted: 0, selector: target.selector, offset: 0, fallback: target.fallback };
    }

    if (!state) {
      const computedMargin = parseFloat(getComputedStyle(root).marginTop);
      state = {
        inlineMarginTop: root.style.getPropertyValue('margin-top'),
        inlinePriority: root.style.getPropertyPriority('margin-top'),
        baseMarginTop: Number.isFinite(computedMargin) ? computedMargin : 0,
        appliedInset: 0
      };
      activeFlowOffsets.set(root, state);
    }

    // Margin participates in normal document flow. Unlike transform/top, it reserves
    // the space and lets the header naturally scroll underneath the translucent status bar.
    root.style.setProperty('margin-top', `${(state.baseMarginTop + needed).toFixed(1)}px`, 'important');
    state.appliedInset = needed;
    return {
      shifted: 1,
      selector: target.selector,
      offset: Math.round(needed),
      fallback: target.fallback
    };
  }

  function describeElement(el) {
    const t = nodeTokens(el);
    const classNames = t.classes.trim().split(/\s+/).filter(Boolean).slice(0, 2);
    return (t.tag || 'element') + (t.id ? `#${t.id}` : '') +
      classNames.map(c => `.${c}`).join('');
  }

  function restoreElement(el, state) {
    try {
      if (state.inlinePosition !== undefined) {
        if (state.inlinePosition) el.style.setProperty('position', state.inlinePosition, state.inlinePositionPriority || '');
        else el.style.removeProperty('position');
      }
      if (state.inlineTop) {
        el.style.setProperty('top', state.inlineTop, state.inlinePriority || '');
      } else {
        el.style.removeProperty('top');
      }
    } catch (e) {
      console.warn('[Summit Probe] could not restore header style:', e);
    }
    activeShifts.delete(el);
  }

  function restorePlayerOverlay(el, state) {
    try {
      if (state.inlineTranslate) {
        el.style.setProperty('translate', state.inlineTranslate, state.inlinePriority || '');
      } else {
        el.style.removeProperty('translate');
      }
    } catch (e) {
      console.warn('[Summit Probe] could not restore player overlay:', e);
    }
    activePlayerOverlayShifts.delete(el);
  }

  function alignWatchPlayerOverlay(candidates) {
    const watchPage = pageKind() === 'watch';
    if (!watchPage || candidates.length === 0) {
      for (const [el, state] of Array.from(activePlayerOverlayShifts.entries())) {
        restorePlayerOverlay(el, state);
      }
      return { shifted: 0, selector: 'none', offset: 0 };
    }

    // The selected masthead/header has already received its safe-area offset by the
    // time this function runs. Use its actual rendered bottom edge, not a guessed height.
    const headerBottom = candidates.reduce((bottom, candidate) => {
      return Math.max(bottom, candidate.el.getBoundingClientRect().bottom);
    }, 0);
    const headerTop = candidates.reduce((top, candidate) => {
      return Math.min(top, candidate.el.getBoundingClientRect().top);
    }, Number.POSITIVE_INFINITY);

    let nodes = [];
    try {
      nodes = Array.from(document.querySelectorAll(PLAYER_TOP_OVERLAY_SELECTOR));
    } catch (e) {
      console.warn('[Summit Probe] player overlay query failed:', e);
    }

    // Prefer outer control rows if a page exposes both a row and its child buttons.
    nodes = nodes.filter(el => !nodes.some(other => other !== el && other.contains(el)));
    const selected = new Set();
    let shifted = 0;
    let totalOffset = 0;
    let selectorSummary = 'none';

    for (const el of nodes) {
      const cs = getComputedStyle(el);
      const rect = el.getBoundingClientRect();
      const visible = cs.display !== 'none' && cs.visibility !== 'hidden' &&
        parseFloat(cs.opacity || '1') > 0.02;
      if (!visible || rect.width < 40 || rect.height < 8 || rect.top > 260 || rect.bottom <= 0) {
        continue;
      }

      const previous = activePlayerOverlayShifts.get(el);
      const previousOffset = previous ? previous.appliedY : 0;
      const baseTop = rect.top - previousOffset;
      const baseBottom = rect.bottom - previousOffset;
      const overlapsHeader = baseTop < headerBottom && baseBottom > headerTop;
      const needed = overlapsHeader ? Math.max(0, headerBottom + 8 - baseTop) : 0;

      if (needed <= 1 || needed > 128) {
        continue;
      }

      let state = previous;
      if (!state) {
        state = {
          inlineTranslate: el.style.getPropertyValue('translate'),
          inlinePriority: el.style.getPropertyPriority('translate'),
          appliedY: 0
        };
        activePlayerOverlayShifts.set(el, state);
      }
      // CSS individual translate composes with the element's existing transform, so
      // YouTube's own player animations and scale transforms are not overwritten.
      el.style.setProperty('translate', `0px ${needed.toFixed(1)}px`, 'important');
      state.appliedY = needed;
      selected.add(el);
      shifted++;
      totalOffset = Math.max(totalOffset, needed);
      selectorSummary = describeElement(el).slice(0, 42);
    }

    for (const [el, state] of Array.from(activePlayerOverlayShifts.entries())) {
      if (!selected.has(el)) restorePlayerOverlay(el, state);
    }

    return { shifted, selector: selectorSummary, offset: Math.round(totalOffset) };
  }

  let pageKindCache = { at: 0, value: 'web' };
  function pageKind() {
    const now = Date.now();
    if (now - pageKindCache.at < 400) return pageKindCache.value;
    const host = location.hostname.toLowerCase();
    let value = 'web';
    if (host.includes('youtube.com')) {
      value = document.querySelector('ytd-watch-flexy, ytm-watch') ? 'watch' : 'youtube';
    }
    pageKindCache = { at: now, value };
    return value;
  }

  // Send this before waiting for document.body or running the DOM scanner.
  window.__summitProbeStatus = 'injected';
  requestPageSettings();
  send({
    type: 'probe-start',
    href: location.href,
    readyState: document.readyState,
    timestamp: Date.now()
  });

  function scan() {
    const startedAt = Date.now();
    lastScan = startedAt;
    timerState.pending = false;
    try {
      if (lastHeaderScrollMode !== headerScrollEnabled) {
        for (const [el, state] of Array.from(activeShifts.entries())) restoreElement(el, state);
        lastHeaderScrollMode = headerScrollEnabled;
      }
      if (!scanStarted) {
        scanStarted = true;
        send({ type: 'scan-start', href: location.href, timestamp: startedAt });
      }

      const root = document.body;
      if (!root) return schedule(500);
      const inset = readInset();
      const vw = window.innerWidth;
      const vh = window.innerHeight;
      const all = root.getElementsByTagName('*');
      const rawCandidates = [];
      const flowCandidates = [];
      let nodes = 0;
      let excludedNodes = 0;
      const scrollY = window.scrollY || window.pageYOffset || 0;
      const allowFlowOffset = pageKind() !== 'watch' && inset > 0;

      for (let i = 0; i < all.length && i < MAX_NODES; i++) {
        nodes++;
        const el = all[i];
        const cs = getComputedStyle(el);
        const pos = cs.position;
        if (cs.display === 'none' || cs.visibility === 'hidden') continue;

        if (pos !== 'fixed' && pos !== 'sticky') {
          // Third header class: normal-flow header. Only inspect elements that carry
          // their own semantic header/navigation hint; descendants inherit neither
          // classification nor an offset from a distant ancestor.
          if (allowFlowOffset && (pos === 'static' || pos === 'relative')) {
            const hint = selfHeaderHint(el);
            if (hint && !isExcludedContent(el) && !containsExcludedContent(el)) {
              const rect = el.getBoundingClientRect();
              if (rect.width >= vw * 0.6 && rect.height >= 24 && rect.height <= vh * 0.45) {
                const bodyRoot = topLevelBodyChild(el, root);
                const prior = bodyRoot ? activeFlowOffsets.get(bodyRoot) : null;
                const docTop = rect.top + scrollY - (prior ? prior.appliedInset : 0);
                if (bodyRoot && docTop >= -24 && docTop <= MAX_FLOW_HEADER_DOC_TOP &&
                    docTop < inset - 2) {
                  flowCandidates.push({
                    root: bodyRoot,
                    anchor: el,
                    selector: describeElement(el),
                    baseDocTop: docTop,
                    hint,
                    fallback: false
                  });
                }
              }
            }
          }
          continue;
        }

        if (isExcludedContent(el) || containsExcludedContent(el)) {
          excludedNodes++;
          continue;
        }

        const top = parseFloat(cs.top);
        if (!Number.isFinite(top) || top < 0 || top > MAX_HEADER_TOP) continue;
        const state = activeShifts.get(el);
        const baseTop = state ? state.baseTop : top;
        const expectedRectTop = state ? (state.baseTop + state.appliedInset) : top;
        const r = el.getBoundingClientRect();
        if (r.width < vw * 0.6 || r.height < 24 || r.height > vh * 0.4) continue;
        if (r.bottom <= 0 || r.top > MAX_HEADER_TOP + 8) continue;
        if (Math.abs(r.top - expectedRectTop) > 7) continue;

        const hint = findHeaderHint(el);
        const hintDepth = findHeaderHintDepth(el);
        // On YouTube watch pages, only the actual navigation node or a very close wrapper
        // may be shifted. A distant ancestor hint is not enough: it can accidentally label
        // player overlays or watch-content wrappers as headers.
        if (pageKind() === 'watch' && (!hint || hintDepth > 2)) continue;
        // A non-zero CSS top is accepted only with a semantic header/navigation hint.
        if (!hint && baseTop > 4) continue;

        rawCandidates.push({
          el,
          top: baseTop,
          width: r.width,
          height: r.height,
          rectTop: r.top,
          hint: hint || 'top-edge',
          hintDepth,
          selector: describeElement(el)
        });
      }

      // If a qualifying header contains another qualifying header, shifting the outer
      // element moves both. Keep only outer roots to avoid double offsets.
      const candidates = rawCandidates.filter(candidate => {
        let parent = candidate.el.parentElement;
        while (parent && parent !== root.parentElement) {
          if (rawCandidates.some(other => other.el === parent)) return false;
          parent = parent.parentElement;
        }
        return true;
      }).sort((a, b) => a.top - b.top || b.width - a.width);

      const selected = new Set(candidates.map(c => c.el));
      if (headerScrollEnabled) {
        for (const [el, state] of activeShifts.entries()) {
          if (!state.headerScrollConverted || !root.contains(el)) continue;
          const style = getComputedStyle(el);
          if (style.display !== 'none' && style.visibility !== 'hidden') selected.add(el);
        }
      }
      // Restore elements which stopped being pinned/header-like after a route or layout change.
      for (const [el, state] of Array.from(activeShifts.entries())) {
        if (!selected.has(el) || inset <= 0) restoreElement(el, state);
      }

      let flowTarget = null;
      if (candidates.length === 0 && allowFlowOffset) {
        flowCandidates.sort((a, b) => a.baseDocTop - b.baseDocTop ||
          (a.fallback ? 1 : 0) - (b.fallback ? 1 : 0));
        flowTarget = flowCandidates[0] || findFallbackFlowRoot(root, vw, inset, scrollY);
      }
      const flowResult = applyFlowOffset(flowTarget, inset, scrollY);

      let shifted = 0;
      const layers = [];
      for (const candidate of candidates) {
        const el = candidate.el;
        if (inset > 0) {
          let state = activeShifts.get(el);
          if (!state) {
            state = {
              baseTop: candidate.top,
              inlineTop: el.style.getPropertyValue('top'),
              inlinePriority: el.style.getPropertyPriority('top'),
              inlinePosition: el.style.getPropertyValue('position'),
              inlinePositionPriority: el.style.getPropertyPriority('position'),
              appliedInset: 0,
              headerScrollConverted: false
            };
            activeShifts.set(el, state);
          }
          if (headerScrollEnabled) {
            const position = getComputedStyle(el).position;
            if (position === 'fixed') {
              const target = state.baseTop + inset;
              el.style.setProperty('position', 'absolute', 'important');
              el.style.setProperty('top', target + 'px', 'important');
              const expectedViewportTop = target - (window.scrollY || 0);
              if (Math.abs(el.getBoundingClientRect().top - expectedViewportTop) > 7) {
                restoreElement(el, state);
                continue;
              }
              state.headerScrollConverted = true;
            } else if (position === 'sticky' && Math.abs(state.baseTop) < 0.5) {
              el.style.setProperty('position', 'relative', 'important');
              el.style.setProperty('top', '0px', 'important');
              state.headerScrollConverted = true;
            } else {
              restoreElement(el, state);
              continue;
            }
          } else {
            const target = state.baseTop + inset;
            el.style.setProperty('top', target + 'px', 'important');
          }
          state.appliedInset = inset;
          shifted++;
        }
        layers.push({
          top: Math.round(candidate.top * 10) / 10,
          y: Math.round(candidate.rectTop),
          height: Math.round(candidate.height),
          tag: candidate.el.tagName.toLowerCase(),
          selector: candidate.selector.slice(0, 72),
          hint: candidate.hint,
          hintDepth: candidate.hintDepth
        });
      }

      // YouTube's watch layout can place the player behind the fixed masthead. Move
      // only the player's top control row out of the masthead's hit/visual area; never
      // translate the video surface, title/metadata, timeline or the whole player.
      const playerOverlay = alignWatchPlayerOverlay(candidates);

      const topLayer = candidates.length ? candidates[0].el : null;
      send({
        type: 'top',
        hasFixed: candidates.length > 0,
        color: topLayer ? solidBackground(topLayer) : '',
        pageColor: pageBackgroundColor(),
        shifted,
        layers: candidates.length,
        layerInfo: layers.slice(0, 5),
        overlayShifted: playerOverlay.shifted,
        playerOverlay: playerOverlay.selector,
        playerOverlayOffset: playerOverlay.offset,
        flowShifted: flowResult.shifted,
        flowSelector: flowResult.selector,
        flowOffset: flowResult.offset,
        flowFallback: flowResult.fallback,
        inset: Math.round(inset * 10) / 10,
        nodes,
        excluded: excludedNodes,
        scanMs: Date.now() - startedAt,
        page: pageKind(),
        host: location.host
      });
    } catch (error) {
      const detail = error && error.message ? error.message : String(error);
      console.error('[Summit Probe] scan failed:', error);
      send({ type: 'scan-error', error: detail.slice(0, 160), href: location.href });
    }
  }

  function run() {
    timerState.pending = true;
    try {
      if (typeof window.requestIdleCallback === 'function') {
        window.requestIdleCallback.call(window, scan, { timeout: 800 });
      } else {
        scan();
      }
    } catch (error) {
      const detail = error && error.message ? error.message : String(error);
      console.error('[Summit Probe] scheduler failed; falling back to direct scan:', error);
      send({
        type: 'scan-error',
        phase: 'scheduler',
        error: detail.slice(0, 160),
        href: location.href
      });
      scan();
    }
  }

  function schedule(delay) {
    if (timerState.pending) return;
    const wait = Math.max(delay || 350, lastScan + MIN_GAP_MS - Date.now());
    const due = Date.now() + wait;
    if (timerState.id !== null && timerState.due <= due) return;
    clearTimeout(timerState.id);
    timerState.due = due;
    timerState.id = setTimeout(() => { timerState.id = null; run(); }, wait);
  }

  document.addEventListener('DOMContentLoaded', () => schedule(150));
  window.addEventListener('load', () => schedule(300));
  window.addEventListener('resize', () => schedule(400));
  window.addEventListener('scroll', () => schedule(900), { passive: true });
  document.addEventListener('pointerdown', () => schedule(250), { passive: true });
  document.addEventListener('touchstart', () => schedule(250), { passive: true });
  try {
    new MutationObserver(records => {
      if (insetEl && records.every(r => r.type === 'childList' &&
          Array.prototype.every.call(r.addedNodes, n => n === insetEl) &&
          Array.prototype.every.call(r.removedNodes, n => n === insetEl))) return;
      schedule(600);
    }).observe(document.documentElement, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ['class', 'aria-hidden']
    });
  } catch (e) {
    console.warn('[Summit Probe] MutationObserver unavailable:', e);
  }
  schedule(200);
})();
