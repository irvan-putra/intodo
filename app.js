/* itdweb review canvas - portable vanilla port of the public React source
   (intodo repo, itdweb/src/App.tsx). Same world, cards, gestures, minimap
   and persistence shape; localStorage replaces the host kit's useLocalState. */
(function () {
    'use strict';

    var WORLD = { w: 1760, h: 1420 };
    var ZMIN = 0.15, ZMAX = 2.5;
    var SAVE_KEY = 'review-canvas.v1';

    var CARD_SIZES = {
        change: { w: 380, h: 300 }, permanence: { w: 380, h: 210 },
        identity: { w: 360, h: 210 }, reviews: { w: 360, h: 200 },
        rule: { w: 360, h: 180 }, source: { w: 360, h: 170 },
        gaps: { w: 380, h: 200 }, property: { w: 320, h: 210 },
        mutation: { w: 320, h: 210 }, fuzz: { w: 320, h: 210 },
        load: { w: 320, h: 210 }, smoke: { w: 320, h: 210 }
    };

    var ICONS = {
        change: '\u{1F4DD}', permanence: '\u{1F4BE}', identity: '\u{1F511}',
        reviews: '\u{1F50D}', source: '\u{1F4E6}', rule: '\u{1F4CF}',
        gaps: '\u26A0\uFE0F', property: '\u{1F501}', mutation: '\u{1F9EC}',
        fuzz: '\u{1F3B2}', load: '\u{1F4C8}', smoke: '\u{1F4A8}'
    };

    var TITLES = {
        change: 'v0.4: narrow widget layout', permanence: 'What persists here',
        identity: 'Exact code identity', reviews: 'Reviews', source: 'The real code',
        rule: 'No hidden gaps', gaps: 'Not checked for v0.4',
        property: 'Property testing', mutation: 'Mutation testing',
        fuzz: 'Fuzz testing', load: 'Load testing', smoke: 'Smoke testing'
    };

    var TAGS = {
        change: 'Change card - example', permanence: 'Permanence, honestly',
        identity: 'Unison idea', reviews: 'crev idea', source: 'Today',
        rule: 'The honest rule', gaps: 'Always visible',
        property: 'Evidence - reported', mutation: 'Planned evidence - not run',
        fuzz: 'Planned evidence - not run', load: 'Planned evidence - not run',
        smoke: 'Planned evidence - not run'
    };

    var BODIES = {
        change: '<p>The widget can shrink to a narrow 2x1 cell and reminders stay readable.<span class="rc-real">real</span></p>' +
            '<p>v0.4 is confirmed working on the owner\'s phone - verified with a screenshot on Nov 12.<span class="rc-real">real</span></p>' +
            '<p class="rc-muted">Reviewed by: no one yet. Proved: nothing.<span class="rc-mock">mock entries</span></p>',
        permanence: '<ul><li>Card positions and your view are remembered on this device and browser (localStorage).</li>' +
            '<li>Not synced across devices; clearing browser storage resets it.</li>' +
            '<li>Long-term ownership (export, backup) is an open question, not a claim.</li></ul>',
        identity: '<p>Each card points at its change by content hash.<span class="rc-mock">mock 9f3c...a21e</span></p>' +
            '<p class="rc-muted">A review attaches to that hash, never to "the latest code". Same hash means same code - it says nothing about whether the code is good.</p>',
        reviews: '<p>No one has reviewed v0.4.</p>' +
            '<p class="rc-muted">Signed reviews from people you choose to trust would appear here, with what each one checked. A review is human judgment, not a scan.</p>',
        source: '<p>The project source is public - the wall of code this canvas is meant to replace for review.</p>' +
            '<p><a href="https://github.com/irvan-putra/intodo">irvan-putra/intodo</a></p>',
        rule: '<p>No badge ever reads "app is secure". Each badge says exactly what was checked, and what has not been checked stays visible in the same size next to it.</p>',
        gaps: '<ul><li>Independent check of the reported property tests (7 Java + 9 JS, run by the builders).</li>' +
            '<li>Any proof - none exists.</li>' +
            '<li>Real cross-client sync: v0.1 failed the owner\'s DeltaChat / ArcaneChat test (Nov 12) - add, delete and done were dead because the page never loaded the webxdc API script. v0.2 fixed that load, and real DeltaChat/ArcaneChat sync was confirmed on-device Nov 12 (add, done, delete all synced). v0.2\'s remaining gap - vague chat notices - is fixed in v0.3 (indeta), which passes a 24-check two-peer simulator test; the v0.3 on-device retest is still pending. The v0.2 fix source is public in the widget repo (commit 715c86b).</li></ul>',
        property: '<p>Random operation sequences; merge invariants must hold (convergence, no lost items).</p>' +
            '<p class="rc-muted">Reported run by the builders on the public merge source (commit 715c86b): 7 Java + 9 JS tests pass. Not independently verified here. Samples cases - weaker than a proof.</p>',
        mutation: '<p>Deliberately break the merge code; the tests must catch each break.</p>' +
            '<p class="rc-muted">Shows whether the tests are effective, not whether the code is secure. Status: not run.<span class="rc-mock">plan</span></p>',
        fuzz: '<p>Random and malformed input at the parsers and the sync protocol boundary.</p>' +
            '<p class="rc-muted">Scope: crash and rejection behavior, not full protocol correctness. Status: not run.<span class="rc-mock">plan</span></p>',
        load: '<p>Realistic multi-device operation volume and resource use.</p>' +
            '<p class="rc-muted">Local and simulated only - no hammering of external services. Status: not run.<span class="rc-mock">plan</span></p>',
        smoke: '<p>A short "does it basically work" pass per build: app starts, list loads, one add, one sync.</p>' +
            '<p class="rc-muted">Fast signal, shallow. Status: not run.<span class="rc-mock">plan</span></p>'
    };

    var DEFAULT_POS = {
        change: { x: 690, y: 430 }, permanence: { x: 690, y: 60 },
        identity: { x: 160, y: 250 }, reviews: { x: 1240, y: 250 },
        source: { x: 160, y: 620 }, rule: { x: 1240, y: 620 },
        gaps: { x: 690, y: 820 }, property: { x: 40, y: 1200 },
        mutation: { x: 400, y: 1200 }, fuzz: { x: 760, y: 1200 },
        load: { x: 1120, y: 1200 }, smoke: { x: 1420, y: 1200 }
    };

    var EDGES = [
        ['permanence', 'change'], ['identity', 'change'], ['reviews', 'change'],
        ['source', 'change'], ['rule', 'change'], ['change', 'gaps'],
        ['change', 'property'], ['change', 'mutation'], ['change', 'fuzz'],
        ['change', 'load'], ['change', 'smoke']
    ];

    // ---------- persistence (localStorage, same {cam, pos} shape) ----------
    function loadSaved() {
        try {
            var raw = localStorage.getItem(SAVE_KEY);
            if (!raw) return null;
            var s = JSON.parse(raw);
            if (!s || !s.cam || typeof s.cam.x !== 'number' || !s.pos) return null;
            return s;
        } catch (e) { return null; }
    }
    function storeSaved(s) {
        try { localStorage.setItem(SAVE_KEY, JSON.stringify(s)); } catch (e) { /* private mode */ }
    }

    var saved = loadSaved();
    var cam = saved ? saved.cam : { x: WORLD.w / 2, y: WORLD.h / 2, z: 0.25 };
    var pos = {};
    Object.keys(DEFAULT_POS).forEach(function (id) {
        pos[id] = (saved && saved.pos && saved.pos[id] &&
            typeof saved.pos[id].x === 'number' && typeof saved.pos[id].y === 'number')
            ? { x: saved.pos[id].x, y: saved.pos[id].y } : { x: DEFAULT_POS[id].x, y: DEFAULT_POS[id].y };
    });

    function persist() { storeSaved({ cam: cam, pos: pos }); savemsg(); }
    function savemsg() {
        document.getElementById('savemsg').textContent =
            'Card positions and view save on this device.';
    }
    if (saved) savemsg();

    // ---------- elements ----------
    var viewport = document.getElementById('viewport');
    var world = document.getElementById('world');
    var edgesSvg = document.getElementById('edges');
    var minimap = document.getElementById('minimap');

    // Square viewport: height follows the element's own rendered width on
    // every screen (no breakpoint, no dvh).
    var vp = { w: 0, h: 0 };
    function measure() {
        var w = viewport.clientWidth;
        if (w > 0) viewport.style.height = w + 'px';
        var nw = viewport.clientWidth, nh = viewport.clientHeight;
        if (nw !== vp.w || nh !== vp.h) {
            vp = { w: nw, h: nh };
            if (!fitted) { fitted = true; if (!saved) fitView(); else render(); }
            else if (!viewTouched && !appliedSaved) fitView();
            else render();
        }
    }
    if (window.ResizeObserver) new ResizeObserver(measure).observe(viewport);
    window.addEventListener('resize', measure);

    // ---------- cards ----------
    var cardEls = {};
    Object.keys(CARD_SIZES).forEach(function (id) {
        var el = document.createElement('div');
        el.className = 'rc-card' + (id === 'change' ? ' rc-center' : '');
        el.dataset.card = id;
        el.style.width = CARD_SIZES[id].w + 'px';
        el.innerHTML =
            '<div class="rc-cardhead"><div class="rc-headtext">' +
            '<span class="rc-icon" aria-hidden="true">' + ICONS[id] + '</span>' +
            '<div><div class="rc-tag">' + TAGS[id] + '</div><h3>' + TITLES[id] + '</h3></div></div>' +
            '<button type="button" class="rc-focus" data-focus="' + id + '">Focus</button></div>' +
            BODIES[id];
        world.appendChild(el);
        cardEls[id] = el;
    });
    world.addEventListener('click', function (e) {
        var b = e.target.closest('[data-focus]');
        if (b) focusCard(b.getAttribute('data-focus'));
    });

    edgesSvg.setAttribute('width', WORLD.w);
    edgesSvg.setAttribute('height', WORLD.h);
    var NS = 'http://www.w3.org/2000/svg';
    var edgeEls = EDGES.map(function () {
        var l = document.createElementNS(NS, 'line');
        l.setAttribute('stroke', 'var(--ds-hairline)');
        l.setAttribute('stroke-width', '2');
        edgesSvg.appendChild(l);
        return l;
    });

    // ---------- minimap ----------
    var MINI_PAD = 4;
    var miniScale = (132 - MINI_PAD * 2) / WORLD.w;
    minimap.style.width = '132px';
    minimap.style.height = Math.round(WORLD.h * miniScale) + MINI_PAD * 2 + 'px';
    var miniCards = {};
    Object.keys(CARD_SIZES).forEach(function (id) {
        var m = document.createElement('div');
        m.className = 'rc-minicard' + (id === 'change' ? ' rc-center' : '');
        m.title = TITLES[id];
        m.style.width = Math.max(14, CARD_SIZES[id].w * miniScale) + 'px';
        m.style.height = Math.max(13, CARD_SIZES[id].h * miniScale) + 'px';
        m.innerHTML = '<span class="rc-miniicon" aria-hidden="true">' + ICONS[id] + '</span>';
        minimap.appendChild(m);
        miniCards[id] = m;
    });
    var miniView = document.createElement('div');
    miniView.className = 'rc-miniview';
    minimap.appendChild(miniView);
    minimap.addEventListener('pointerdown', function (e) {
        e.stopPropagation();
        viewTouched = true;
        var r = minimap.getBoundingClientRect();
        cam = clampCam({
            x: (e.clientX - r.left - MINI_PAD) / miniScale,
            y: (e.clientY - r.top - MINI_PAD) / miniScale,
            z: cam.z
        });
        persist(); render();
    });

    // ---------- camera ----------
    var fitted = false, appliedSaved = !!saved, viewTouched = false;

    function center(id, p) {
        var s = CARD_SIZES[id];
        return { x: p[id].x + s.w / 2, y: p[id].y + s.h / 2 };
    }
    function clampZ(z) { return Math.min(ZMAX, Math.max(ZMIN, z)); }
    function clampCam(c) {
        return {
            x: Math.min(WORLD.w + 120, Math.max(-120, c.x)),
            y: Math.min(WORLD.h + 120, Math.max(-120, c.y)),
            z: c.z
        };
    }
    function evp() { return vp.w > 0 ? vp : { w: 390, h: 390 }; }
    function toWorld(p, c) {
        var v = evp();
        return { x: c.x + (p.x - v.w / 2) / c.z, y: c.y + (p.y - v.h / 2) / c.z };
    }
    function fitView() {
        var v = evp();
        if (!v.w || !v.h) return;
        cam = { x: WORLD.w / 2, y: WORLD.h / 2, z: clampZ(Math.min(v.w / WORLD.w, v.h / WORLD.h) * 0.98) };
        persist(); render();
    }
    function zoomBy(f) {
        viewTouched = true;
        cam = clampCam({ x: cam.x, y: cam.y, z: clampZ(cam.z * f) });
        persist(); render();
    }
    function focusCard(id) {
        viewTouched = true;
        var ctr = center(id, pos);
        var v = evp();
        cam = clampCam({ x: ctr.x, y: ctr.y, z: clampZ(Math.min(v.w / (CARD_SIZES[id].w + 80), 1.2)) });
        persist(); render();
    }
    document.getElementById('zin').addEventListener('click', function () { zoomBy(1.35); });
    document.getElementById('zout').addEventListener('click', function () { zoomBy(1 / 1.35); });
    document.getElementById('fit').addEventListener('click', function () { viewTouched = true; fitView(); });

    // ---------- gestures ----------
    var pointers = new Map();
    var gst = { mode: null, cardId: null, start: null, last: null, off: null, moved: false, pinch: null };

    viewport.addEventListener('pointerdown', function (e) {
        var t = e.target;
        var p = { x: e.clientX, y: e.clientY };
        pointers.set(e.pointerId, p);
        gst.moved = false;
        if (pointers.size === 2) {
            var ab = Array.from(pointers.values());
            gst.mode = 'pinch';
            gst.pinch = {
                d0: Math.hypot(ab[0].x - ab[1].x, ab[0].y - ab[1].y),
                z0: cam.z, cam0: { x: cam.x, y: cam.y, z: cam.z },
                mid: { x: (ab[0].x + ab[1].x) / 2, y: (ab[0].y + ab[1].y) / 2 }
            };
            return;
        }
        if (t.closest('button') || t.closest('a') || t.closest('.rc-minimap') || t.closest('.rc-controls')) { gst.mode = null; return; }
        gst.start = p; gst.last = p;
        var cardEl = t.closest('[data-card]');
        if (cardEl) {
            var id = cardEl.dataset.card;
            gst.mode = 'card'; gst.cardId = id;
            var w = toWorld(p, cam);
            gst.off = { x: w.x - pos[id].x, y: w.y - pos[id].y };
        } else {
            gst.mode = 'pan';
        }
        viewport.classList.add('rc-dragging');
    });

    viewport.addEventListener('pointermove', function (e) {
        if (!pointers.has(e.pointerId)) return;
        var p = { x: e.clientX, y: e.clientY };
        pointers.set(e.pointerId, p);
        if (!gst.moved && gst.mode && Math.hypot(p.x - gst.start.x, p.y - gst.start.y) > 6) {
            gst.moved = true;
            try { viewport.setPointerCapture(e.pointerId); } catch (err) { /* noop */ }
        }
        if (gst.mode === 'pinch' && gst.pinch && pointers.size >= 2) {
            var ab = Array.from(pointers.values());
            var d = Math.hypot(ab[0].x - ab[1].x, ab[0].y - ab[1].y);
            var mid = { x: (ab[0].x + ab[1].x) / 2, y: (ab[0].y + ab[1].y) / 2 };
            var z = clampZ(gst.pinch.z0 * (d / (gst.pinch.d0 || 1)));
            var mw = toWorld(mid, gst.pinch.cam0);
            var v = evp();
            cam = clampCam({ x: mw.x - (mid.x - v.w / 2) / z, y: mw.y - (mid.y - v.h / 2) / z, z: z });
            render();
        } else if (gst.mode === 'card' && gst.cardId) {
            var w = toWorld(p, cam);
            pos[gst.cardId] = { x: Math.round(w.x - gst.off.x), y: Math.round(w.y - gst.off.y) };
            render();
        } else if (gst.mode === 'pan') {
            var dx = (p.x - gst.last.x) / cam.z, dy = (p.y - gst.last.y) / cam.z;
            gst.last = p;
            cam = clampCam({ x: cam.x - dx, y: cam.y - dy, z: cam.z });
            render();
        }
    });

    function endPointer(e) {
        pointers.delete(e.pointerId);
        if (gst.mode === 'pinch' && pointers.size === 1) {
            var remaining = Array.from(pointers.values())[0];
            gst.mode = 'pan'; gst.start = remaining; gst.last = remaining;
        } else if (pointers.size === 0) {
            if (gst.mode) { viewTouched = true; persist(); }
            gst.mode = null;
            viewport.classList.remove('rc-dragging');
        }
    }
    viewport.addEventListener('pointerup', endPointer);
    viewport.addEventListener('pointercancel', endPointer);
    viewport.addEventListener('click', function (e) {
        if (gst.moved) { e.stopPropagation(); e.preventDefault(); gst.moved = false; }
    }, true);

    // ---------- render ----------
    function render() {
        var v = evp();
        world.style.width = WORLD.w + 'px';
        world.style.height = WORLD.h + 'px';
        world.style.transform =
            'translate(' + (v.w / 2 - cam.x * cam.z) + 'px, ' + (v.h / 2 - cam.y * cam.z) + 'px) scale(' + cam.z + ')';
        Object.keys(cardEls).forEach(function (id) {
            cardEls[id].style.left = pos[id].x + 'px';
            cardEls[id].style.top = pos[id].y + 'px';
        });
        EDGES.forEach(function (pr, i) {
            var ca = center(pr[0], pos), cb = center(pr[1], pos);
            edgeEls[i].setAttribute('x1', ca.x); edgeEls[i].setAttribute('y1', ca.y);
            edgeEls[i].setAttribute('x2', cb.x); edgeEls[i].setAttribute('y2', cb.y);
        });
        Object.keys(miniCards).forEach(function (id) {
            miniCards[id].style.left = MINI_PAD + pos[id].x * miniScale + 'px';
            miniCards[id].style.top = MINI_PAD + pos[id].y * miniScale + 'px';
        });
        miniView.style.left = MINI_PAD + (cam.x - v.w / (2 * cam.z)) * miniScale + 'px';
        miniView.style.top = MINI_PAD + (cam.y - v.h / (2 * cam.z)) * miniScale + 'px';
        miniView.style.width = (v.w / cam.z) * miniScale + 'px';
        miniView.style.height = (v.h / cam.z) * miniScale + 'px';
    }

    measure();
    render();
})();
