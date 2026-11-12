// canvas.js - itdxdc review canvas. Plain-DOM port of the itdweb React canvas
// (itdweb/src/App.tsx): same world, cards, gestures, minimap and on-device
// layout persistence, adapted to the webxdc dark theme. No dependencies;
// camera math lives in js/canvas-math.js (shared with the Node test suite).
//
// Layout persistence is local to this device (localStorage); it is not sent
// as webxdc updates and not synced between chat members - same honesty as the
// itdweb page: implemented, per-device, cleared with webview storage.

(function () {
  'use strict';

  const M = window.CanvasMath;
  const WORLD = window.CANVAS_WORLD;
  const CARD_SIZES = window.CANVAS_CARD_SIZES;
  const EDGES = window.CANVAS_EDGES;
  const STORE_KEY = window.CANVAS_STORE_KEY;

  const ICONS = {
    change: '\u{1F4DD}', permanence: '\u{1F4BE}', identity: '\u{1F511}',
    reviews: '\u{1F50D}', source: '\u{1F4E6}', rule: '\u{1F4CF}',
    gaps: '\u26A0\uFE0F', property: '\u{1F501}', mutation: '\u{1F9EC}',
    fuzz: '\u{1F3B2}', load: '\u{1F4C8}', smoke: '\u{1F4A8}',
  };

  // Card content, verbatim from itdweb/src/App.tsx. Each block is a paragraph
  // ('p', 'muted'), bullet list ('ul') or link ('link'). Inline chips are
  // {chip:'real'|'mock', label} segments inside paragraph text arrays.
  const CARDS = {
    change: {
      tag: 'Change card - example', title: 'v0.4: narrow widget layout', center: true,
      blocks: [
        { p: ['The widget can shrink to a narrow 2x1 cell and reminders stay readable.', { chip: 'real', label: 'real' }] },
        { p: ['v0.4 is confirmed working on your phone - you verified it with a screenshot on Nov 12.', { chip: 'real', label: 'real' }] },
        { muted: ['Reviewed by: no one yet. Proved: nothing.', { chip: 'mock', label: 'mock entries' }] },
      ],
    },
    permanence: {
      tag: 'Permanence, honestly', title: 'What persists here',
      blocks: [
        { ul: [
          'Card positions and your view are designed to be remembered on this device and browser. Implemented, not yet verified in your signed-in view.',
          'Not synced across devices; clearing browser storage resets it.',
          'Long-term ownership (export, backup) is an open question, not a claim.',
        ] },
      ],
    },
    identity: {
      tag: 'Unison idea', title: 'Exact code identity',
      blocks: [
        { p: ['Each card points at its change by content hash.', { chip: 'mock', label: 'mock 9f3c...a21e' }] },
        { muted: ['A review attaches to that hash, never to "the latest code". Same hash means same code - it says nothing about whether the code is good.'] },
      ],
    },
    reviews: {
      tag: 'crev idea', title: 'Reviews',
      blocks: [
        { p: ['No one has reviewed v0.4.'] },
        { muted: ['Signed reviews from people you choose to trust would appear here, with what each one checked. A review is human judgment, not a scan.'] },
      ],
    },
    source: {
      tag: 'Today', title: 'The real code',
      blocks: [
        { p: ['The project source is public - the wall of code this canvas is meant to replace for review.'] },
        { link: { href: 'https://github.com/irvan-putra/intodo', label: 'irvan-putra/intodo' } },
      ],
    },
    rule: {
      tag: 'The honest rule', title: 'No hidden gaps',
      blocks: [
        { p: ['No badge ever reads "app is secure". Each badge says exactly what was checked, and what was not checked stays visible in the same size next to it.'] },
      ],
    },
    gaps: {
      tag: 'Always visible', title: 'Not checked for v0.4',
      blocks: [
        { ul: [
          'Independent check of the reported property tests (7 Java + 9 JS, run by the builders).',
          'Any proof - none exists.',
          'Real cross-client sync: v0.1 failed your DeltaChat / ArcaneChat test (Nov 12) - add, delete and done were dead because the page never loaded the webxdc API script. v0.2 fixed that load, and you confirmed real DeltaChat/ArcaneChat sync on-device Nov 12 (add, done, delete all synced). v0.2\'s remaining gap - vague chat notices - is fixed in v0.3 (current build v0.3.1 keeps conflict history collapsed inside an open pick-one sheet too); both pass a 24-check two-peer simulator test; the on-device retest is still pending. The v0.2 fix source is public in the repo intodo (commit 715c86b).',
        ] },
      ],
    },
    property: {
      tag: 'Evidence - reported', title: 'Property testing',
      blocks: [
        { p: ['Random operation sequences; merge invariants must hold (convergence, no lost items).'] },
        { muted: ['Reported run by the builders on the public merge source (repo intodo, commit 715c86b): 7 Java + 9 JS tests pass. Not independently verified here. Samples cases - weaker than a proof.'] },
      ],
    },
    mutation: {
      tag: 'Planned evidence - not run', title: 'Mutation testing',
      blocks: [
        { p: ['Deliberately break the merge code; the tests must catch each break.'] },
        { muted: ['Shows whether the tests are effective, not whether the code is secure. Status: not run.', { chip: 'mock', label: 'plan' }] },
      ],
    },
    fuzz: {
      tag: 'Planned evidence - not run', title: 'Fuzz testing',
      blocks: [
        { p: ['Random and malformed input at the parsers and the sync protocol boundary.'] },
        { muted: ['Scope: crash and rejection behavior, not full protocol correctness. Status: not run.', { chip: 'mock', label: 'plan' }] },
      ],
    },
    load: {
      tag: 'Planned evidence - not run', title: 'Load testing',
      blocks: [
        { p: ['Realistic multi-device operation volume and resource use.'] },
        { muted: ['Local and simulated only - no hammering of external services. Status: not run.', { chip: 'mock', label: 'plan' }] },
      ],
    },
    smoke: {
      tag: 'Planned evidence - not run', title: 'Smoke testing',
      blocks: [
        { p: ['A short "does it basically work" pass per build: app starts, list loads, one add, one sync.'] },
        { muted: ['Fast signal, shallow. Status: not run.', { chip: 'mock', label: 'plan' }] },
      ],
    },
  };

  function el(tag, cls, parent) {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (parent) parent.appendChild(e);
    return e;
  }

  function renderBlock(card, block) {
    if (block.ul) {
      const ul = el('ul', null, card);
      for (const item of block.ul) el('li', null, ul).textContent = item;
      return;
    }
    if (block.link) {
      const p = el('p', null, card);
      const a = el('a', null, p);
      a.href = block.link.href;
      a.target = '_blank';
      a.rel = 'noopener noreferrer';
      a.textContent = block.link.label;
      return;
    }
    const segs = block.p || block.muted;
    const p = el('p', block.muted ? 'rc-muted' : null, card);
    for (const seg of segs) {
      if (typeof seg === 'string') p.appendChild(document.createTextNode(seg));
      else el('span', seg.chip === 'real' ? 'rc-real' : 'rc-mock', p).textContent = seg.label;
    }
  }

  function mount(root) {
    const saved = M.parseSaved(safeGet(STORE_KEY));
    let cam = saved ? saved.cam : { x: WORLD.w / 2, y: WORLD.h / 2, z: 0.25 };
    let pos = saved ? saved.pos : M.defaultPos();
    let vp = { w: 0, h: 0 };
    let fitted = false;
    let viewTouched = false;
    let dragging = false;
    const pointers = new Map();
    const g = { mode: null, cardId: null, start: null, last: null, off: null, moved: false, pinch: null };

    const viewport = el('div', 'rc-viewport', root);
    const world = el('div', 'rc-world', viewport);
    world.style.width = WORLD.w + 'px';
    world.style.height = WORLD.h + 'px';

    const NS = 'http://www.w3.org/2000/svg';
    const svg = document.createElementNS(NS, 'svg');
    svg.setAttribute('class', 'rc-edges');
    svg.setAttribute('width', WORLD.w);
    svg.setAttribute('height', WORLD.h);
    world.appendChild(svg);
    const edgeLines = EDGES.map(([a, b]) => {
      const line = document.createElementNS(NS, 'line');
      line.setAttribute('stroke-width', '2');
      line.dataset.a = a; line.dataset.b = b;
      svg.appendChild(line);
      return line;
    });

    const cardWraps = {};
    const miniCards = {};
    for (const id of Object.keys(CARDS)) {
      const wrap = el('div', 'rc-cardwrap', world);
      wrap.style.width = CARD_SIZES[id].w + 'px';
      const card = el('div', 'rc-card' + (CARDS[id].center ? ' rc-center' : ''), wrap);
      card.dataset.card = id;
      const head = el('div', 'rc-cardhead', card);
      const headtext = el('div', 'rc-headtext', head);
      const icon = el('span', 'rc-icon', headtext);
      icon.setAttribute('aria-hidden', 'true');
      icon.textContent = ICONS[id];
      const tt = el('div', null, headtext);
      el('div', 'rc-tag', tt).textContent = CARDS[id].tag;
      el('h3', null, tt).textContent = CARDS[id].title;
      const focus = el('button', 'rc-focus', head);
      focus.type = 'button';
      focus.textContent = 'Focus';
      focus.addEventListener('click', () => focusCard(id));
      for (const block of CARDS[id].blocks) renderBlock(card, block);
      cardWraps[id] = wrap;
    }

    const controls = el('div', 'rc-controls', viewport);
    const btnIn = el('button', null, controls);
    btnIn.type = 'button'; btnIn.setAttribute('aria-label', 'Zoom in'); btnIn.textContent = '+';
    btnIn.addEventListener('click', () => zoomBy(1.35));
    const btnOut = el('button', null, controls);
    btnOut.type = 'button'; btnOut.setAttribute('aria-label', 'Zoom out'); btnOut.textContent = '-';
    btnOut.addEventListener('click', () => zoomBy(1 / 1.35));
    const btnFit = el('button', 'rc-fit', controls);
    btnFit.type = 'button'; btnFit.textContent = 'Fit';
    btnFit.addEventListener('click', () => { viewTouched = true; fitView(); });

    const minimap = el('div', 'rc-minimap', viewport);
    minimap.style.width = window.CANVAS_MINI_W + 'px';
    minimap.style.height = M.miniHeight() + 'px';
    for (const id of Object.keys(CARD_SIZES)) {
      const mc = el('div', 'rc-minicard' + (id === 'change' ? ' rc-center' : ''), minimap);
      mc.title = CARDS[id] ? CARDS[id].title : id;
      const mi = el('span', 'rc-miniicon', mc);
      mi.setAttribute('aria-hidden', 'true');
      mi.textContent = ICONS[id] || '';
      miniCards[id] = mc;
    }
    const miniview = el('div', 'rc-miniview', minimap);

    const caption = el('div', 'rc-caption', root);
    caption.textContent = 'Drag the background to pan, drag a card to move it, pinch or use + and - to zoom, tap a card\'s Focus to read it. The canvas is bounded, not infinite. Layout is remembered on this device only.';

    function render() {
      const zd = cam.z;
      world.style.transform =
        'translate(' + (vp.w / 2 - cam.x * zd) + 'px, ' + (vp.h / 2 - cam.y * zd) + 'px) scale(' + zd + ')';
      for (const id of Object.keys(cardWraps)) {
        cardWraps[id].style.left = pos[id].x + 'px';
        cardWraps[id].style.top = pos[id].y + 'px';
      }
      for (const line of edgeLines) {
        const ca = M.center(line.dataset.a, pos), cb = M.center(line.dataset.b, pos);
        line.setAttribute('x1', ca.x); line.setAttribute('y1', ca.y);
        line.setAttribute('x2', cb.x); line.setAttribute('y2', cb.y);
      }
      const ms = M.miniScale();
      for (const id of Object.keys(miniCards)) {
        const s = CARD_SIZES[id];
        miniCards[id].style.left = (window.CANVAS_MINI_PAD + pos[id].x * ms) + 'px';
        miniCards[id].style.top = (window.CANVAS_MINI_PAD + pos[id].y * ms) + 'px';
        miniCards[id].style.width = Math.max(14, s.w * ms) + 'px';
        miniCards[id].style.height = Math.max(13, s.h * ms) + 'px';
      }
      const r = M.miniViewRect(cam, vp);
      miniview.style.left = r.left + 'px';
      miniview.style.top = r.top + 'px';
      miniview.style.width = r.width + 'px';
      miniview.style.height = r.height + 'px';
      viewport.classList.toggle('rc-dragging', dragging);
    }

    function persist() {
      safeSet(STORE_KEY, M.serializeSaved({ cam, pos }));
    }

    function fitView() {
      const w = viewport.clientWidth, h = viewport.clientHeight;
      if (!w || !h) return;
      cam = M.fitCam(w, h);
      render();
      persist();
    }

    function zoomBy(f) {
      viewTouched = true;
      cam = M.zoomCam(cam, f);
      render();
      persist();
    }

    function focusCard(id) {
      viewTouched = true;
      cam = M.focusCam(id, pos, vp.w || viewport.clientWidth);
      render();
      persist();
    }

    function localPoint(e) {
      const r = viewport.getBoundingClientRect();
      return { x: e.clientX - r.left, y: e.clientY - r.top };
    }

    viewport.addEventListener('pointerdown', (e) => {
      const t = e.target;
      const p = localPoint(e);
      pointers.set(e.pointerId, p);
      g.moved = false;
      if (pointers.size === 2) {
        const [a, b] = [...pointers.values()];
        g.mode = 'pinch';
        g.pinch = { d0: Math.hypot(a.x - b.x, a.y - b.y), z0: cam.z, cam0: cam, mid: { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 } };
        return;
      }
      if (t.closest('button') || t.closest('a')) { g.mode = null; return; }
      if (t.closest('.rc-minimap')) {
        g.mode = 'minimap';
        viewTouched = true;
        const r = minimap.getBoundingClientRect();
        cam = M.camFromMiniPoint(e.clientX - r.left, e.clientY - r.top, cam);
        render();
        return;
      }
      if (t.closest('.rc-controls')) { g.mode = null; return; }
      g.start = p; g.last = p;
      const cardEl = t.closest('[data-card]');
      if (cardEl) {
        const id = cardEl.dataset.card;
        g.mode = 'card'; g.cardId = id;
        const w = M.toWorld(p, cam, vp);
        g.off = { x: w.x - pos[id].x, y: w.y - pos[id].y };
      } else {
        g.mode = 'pan';
      }
      dragging = true;
      render();
    });

    viewport.addEventListener('pointermove', (e) => {
      if (!pointers.has(e.pointerId)) return;
      const p = localPoint(e);
      pointers.set(e.pointerId, p);
      if (!g.moved && g.mode && g.start && Math.hypot(p.x - g.start.x, p.y - g.start.y) > 6) {
        g.moved = true;
        try { viewport.setPointerCapture(e.pointerId); } catch (err) { /* noop */ }
      }
      if (g.mode === 'pinch' && g.pinch && pointers.size >= 2) {
        const [a, b] = [...pointers.values()];
        const d = Math.hypot(a.x - b.x, a.y - b.y);
        const mid = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 };
        cam = M.pinchCam(g.pinch, mid, d, vp);
        render();
      } else if (g.mode === 'card' && g.cardId) {
        const w = M.toWorld(p, cam, vp);
        pos = M.dragPos(pos, g.cardId, w, g.off);
        render();
      } else if (g.mode === 'pan') {
        cam = M.panCam(cam, p.x - g.last.x, p.y - g.last.y);
        g.last = p;
        render();
      } else if (g.mode === 'minimap') {
        const r = minimap.getBoundingClientRect();
        cam = M.camFromMiniPoint(e.clientX - r.left, e.clientY - r.top, cam);
        render();
      }
    });

    function endPointer(e) {
      pointers.delete(e.pointerId);
      if (g.mode === 'pinch' && pointers.size === 1) {
        const remaining = [...pointers.values()][0];
        g.mode = 'pan'; g.start = remaining; g.last = remaining;
      } else if (pointers.size === 0) {
        if (g.mode) { viewTouched = true; persist(); }
        g.mode = null; g.cardId = null;
        dragging = false;
        render();
      }
    }
    viewport.addEventListener('pointerup', endPointer);
    viewport.addEventListener('pointercancel', endPointer);

    viewport.addEventListener('click', (e) => {
      if (g.moved) { e.stopPropagation(); e.preventDefault(); g.moved = false; }
    }, true);

    const ro = new ResizeObserver(() => {
      vp = { w: viewport.clientWidth, h: viewport.clientHeight };
      if (vp.w <= 0) return;
      if (!fitted) {
        fitted = true;
        if (!saved) fitView(); else render();
      } else if (!viewTouched && !saved) {
        fitView();
      } else {
        render();
      }
    });
    ro.observe(viewport);

    render();

    // state hook for automated checks (read-only snapshots)
    return {
      getCam: () => ({ x: cam.x, y: cam.y, z: cam.z }),
      getPos: () => JSON.parse(JSON.stringify(pos)),
      cardCount: () => Object.keys(cardWraps).length,
    };
  }

  function safeGet(k) {
    try { return window.localStorage.getItem(k); } catch (e) { return null; }
  }
  function safeSet(k, v) {
    try { window.localStorage.setItem(k, v); } catch (e) { /* storage unavailable: session-only layout */ }
  }

  window.ReviewCanvas = { mount };
  window.reviewCanvas = null; // set by canvas.html bootstrap
})();
