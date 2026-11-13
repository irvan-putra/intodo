import React, { useEffect, useRef, useState } from 'react';
import { TextLink, useLocalState } from './files-shim';
import { COMMIT, COMMIT_SHORT, SECTIONS, Section } from '../src/sections';

type Pos = { x: number; y: number };
type Cam = { x: number; y: number; z: number };
type Mark = 'reviewed' | 'flag';
type Saved = { cam: Cam; pos: Record<string, Pos>; reviews?: Record<string, Mark> };

const WORLD = { w: 2160, h: 2560 };
const ZMIN = 0.15, ZMAX = 2.5;

const CARD_SIZES: Record<string, { w: number; h: number }> = {
    progress: { w: 360, h: 210 },
    howto: { w: 360, h: 260 },
    permanence: { w: 360, h: 250 },
    rule: { w: 360, h: 190 },
};
// measured real card heights run up to ~472px (fit view, unexpanded); the row
// pitch below must clear the tallest card, not this estimate
for (const s of SECTIONS) CARD_SIZES[s.id] = { w: 360, h: 480 };

const ICONS: Record<string, string> = {
    progress: '\u{1F3AF}',
    howto: '\u{1F9ED}',
    permanence: '\u{1F4BE}',
    rule: '\u{1F4CF}',
};
for (const s of SECTIONS) ICONS[s.id] = s.icon;

const TITLES: Record<string, string> = {
    progress: 'Review progress',
    howto: 'How to review a section',
    permanence: 'What persists here',
    rule: 'No hidden gaps',
};
for (const s of SECTIONS) TITLES[s.id] = s.title;

const COL_X = [20, 460, 900, 1340, 1780];
// 500px pitch: tallest measured card is 472px, so rows never overlap unexpanded
const ROW_Y = [40, 540, 1040, 1540, 2040];
const DEFAULT_POS: Record<string, Pos> = {
    'list-ui':       { x: COL_X[0], y: ROW_Y[0] },
    'feed-sync':     { x: COL_X[1], y: ROW_Y[0] },
    'self-update':   { x: COL_X[2], y: ROW_Y[0] },
    'native-canvas': { x: COL_X[3], y: ROW_Y[0] },
    'merge-core':    { x: COL_X[4], y: ROW_Y[0] },
    'bot-app':       { x: COL_X[0], y: ROW_Y[1] },
    'bot-runtime':   { x: COL_X[1], y: ROW_Y[1] },
    'bot-delivery':  { x: COL_X[2], y: ROW_Y[1] },
    'witness':       { x: COL_X[3], y: ROW_Y[1] },
    'rule':          { x: COL_X[4], y: ROW_Y[1] },
    'xdc-app':       { x: COL_X[0], y: ROW_Y[2] },
    'xdc-canvas':    { x: COL_X[1], y: ROW_Y[2] },
    'this-page':     { x: COL_X[2], y: ROW_Y[2] },
    'permanence':    { x: COL_X[3], y: ROW_Y[2] },
    'howto':         { x: COL_X[4], y: ROW_Y[2] },
    'tests-android': { x: COL_X[0], y: ROW_Y[3] },
    'tests-bot':     { x: COL_X[1], y: ROW_Y[3] },
    'tests-xdc':     { x: COL_X[2], y: ROW_Y[3] },
    'progress':      { x: COL_X[3], y: ROW_Y[3] },
    'build-ci':      { x: COL_X[0], y: ROW_Y[4] },
    'feed-tool':     { x: COL_X[1], y: ROW_Y[4] },
    'assets-docs':   { x: COL_X[2], y: ROW_Y[4] },
};

const EDGES: [string, string][] = [
    ['tests-android', 'list-ui'],
    ['tests-android', 'feed-sync'],
    ['tests-android', 'self-update'],
    ['tests-android', 'native-canvas'],
    ['tests-android', 'merge-core'],
    ['tests-bot', 'bot-runtime'],
    ['tests-bot', 'bot-delivery'],
    ['tests-bot', 'witness'],
    ['tests-xdc', 'xdc-app'],
    ['tests-xdc', 'xdc-canvas'],
    ['native-canvas', 'xdc-canvas'],
    ['merge-core', 'xdc-app'],
    ['bot-app', 'bot-runtime'],
    ['bot-runtime', 'bot-delivery'],
    ['feed-sync', 'feed-tool'],
    ['build-ci', 'tests-android'],
];

function center(id: string, pos: Record<string, Pos>): Pos {
    const s = CARD_SIZES[id];
    return { x: pos[id].x + s.w / 2, y: pos[id].y + s.h / 2 };
}

// keep a saved position only if it lands clear of every other card's slot;
// positions saved against an older card set would otherwise overlap the grid
function sanePositions(savedPos: Record<string, Pos>): Record<string, Pos> {
    const out: Record<string, Pos> = { ...DEFAULT_POS };
    for (const id of Object.keys(DEFAULT_POS)) {
        const p = savedPos[id];
        if (!p) continue;
        const s = CARD_SIZES[id];
        const hit = Object.keys(DEFAULT_POS).some((other) => {
            if (other === id) return false;
            const q = DEFAULT_POS[other];
            const o = CARD_SIZES[other];
            return p.x < q.x + o.w && p.x + s.w > q.x && p.y < q.y + o.h && p.y + s.h > q.y;
        });
        if (!hit) out[id] = p;
    }
    return out;
}

const clampZ = (z: number) => Math.min(ZMAX, Math.max(ZMIN, z));
// infinite canvas: pan is deliberately unbounded; zoom stays clamped at call sites
const clampCam = (c: Cam): Cam => c;

function CardShell({ id, tag, title, mark, centerCard, onFocus, children }: {
    id: string; tag: string; title: string; mark?: Mark; centerCard?: boolean; onFocus: (id: string) => void; children: React.ReactNode;
}) {
    return <div className={`rc-card${centerCard ? ' rc-center' : ''}`} data-card={id}>
        <div className="rc-cardhead">
            <div className="rc-headtext">
                <span className="rc-icon" aria-hidden="true">{ICONS[id]}</span>
                <div>
                    <div className="rc-tag">{tag}</div>
                    <h3>{title}</h3>
                </div>
            </div>
            <button type="button" className="rc-focus" onClick={() => onFocus(id)}>Focus</button>
        </div>
        {mark === 'reviewed' && <span className="rc-badge rc-badge-done">Reviewed</span>}
        {mark === 'flag' && <span className="rc-badge rc-badge-flag">Another look</span>}
        {children}
    </div>;
}

function SectionCard({ s, mark, expanded, onToggleExpand, onMark, onFocus }: {
    s: Section; mark?: Mark; expanded: boolean; onToggleExpand: (id: string) => void; onMark: (id: string, m?: Mark) => void; onFocus: (id: string) => void;
}) {
    const shown = expanded ? s.files : s.files.slice(0, 5);
    const cycle = () => {
        if (!mark) onMark(s.id, 'reviewed');
        else if (mark === 'reviewed') onMark(s.id, 'flag');
        else onMark(s.id, undefined);
    };
    const label = !mark ? 'Mark reviewed' : mark === 'reviewed' ? 'Needs another look' : 'Clear mark';
    return <CardShell id={s.id} tag={s.tag} title={s.title} mark={mark} onFocus={onFocus}>
        <p>{s.purpose}</p>
        <ul className="rc-files">
            {shown.map((fl) => (
                <li key={fl.path}><TextLink href={`https://github.com/irvan-putra/intodo/blob/${COMMIT}/${fl.path}`}><code>{fl.path.split('/').pop()}</code></TextLink>{fl.lines != null ? ` - ${fl.lines} lines` : fl.note ? ` - ${fl.note}` : ''}</li>
            ))}
        </ul>
        {s.files.length > 5 && (
            <div className="rc-reviewbtns">
                <button type="button" onClick={() => onToggleExpand(s.id)}>{expanded ? 'Show fewer files' : `Show all ${s.files.length} files`}</button>
            </div>
        )}
        <p className="rc-digest">repo {COMMIT_SHORT} - {s.totalLines.toLocaleString()} lines - digest {s.digest}</p>
        <p className="rc-nchead">Not checked yet</p>
        <ul>
            {s.notChecked.map((n) => <li key={n}>{n}</li>)}
        </ul>
        <p><TextLink href={`https://github.com/irvan-putra/intodo/tree/${COMMIT}/${s.link}`}>Open this folder at {COMMIT_SHORT}</TextLink></p>
        <div className="rc-reviewbtns">
            <button type="button" onClick={cycle}>{label}</button>
        </div>
    </CardShell>;
}

function Canvas() {
    const [saved, setSaved, savedMeta] = useLocalState<Saved | null>('review-canvas.v1', null);
    const [cam, setCam] = useState<Cam>(saved?.cam ?? { x: WORLD.w / 2, y: WORLD.h / 2, z: 0.25 });
    const [pos, setPos] = useState<Record<string, Pos>>(sanePositions(saved?.pos ?? {}));
    // focused card on a narrow screen: minimap collapses to a chip so it
    // never covers the card's lower content or review controls
    const [focusedId, setFocusedId] = useState<string | null>(null);
    const [reviews, setReviews] = useState<Record<string, Mark>>(saved?.reviews ?? {});
    const [expandedId, setExpandedId] = useState<string | null>(null);
    const [vp, setVp] = useState({ w: 0, h: 0 });
    const fitted = useRef(false);
    const appliedSaved = useRef(false);
    const viewTouched = useRef(false);
    const [dragging, setDragging] = useState(false);
    const vpRef = useRef<HTMLDivElement>(null);
    const pointers = useRef(new Map<number, Pos>());
    const g = useRef<{
        mode: 'pan' | 'card' | 'pinch' | null; cardId?: string;
        start: Pos; last: Pos; off: Pos; moved: boolean;
        pinch?: { d0: number; z0: number; cam0: Cam; mid: Pos };
    }>({ mode: null, start: { x: 0, y: 0 }, last: { x: 0, y: 0 }, off: { x: 0, y: 0 }, moved: false });

    useEffect(() => {
        const el = vpRef.current;
        if (!el) return;
        // full-screen canvas: the viewport element fills the window (100dvh in
        // static.css), so just measure it. Deferred to the next frame - writing
        // state from inside the RO callback can trigger "ResizeObserver loop"
        // errors on mobile.
        const measure = () => {
            requestAnimationFrame(() => {
                setVp((prev) => (Math.abs(prev.w - el.clientWidth) < 1 && Math.abs(prev.h - el.clientHeight) < 1)
                    ? prev
                    : { w: el.clientWidth, h: el.clientHeight });
            });
        };
        const ro = new ResizeObserver(measure);
        ro.observe(el);
        measure();
        return () => ro.disconnect();
    }, []);

    // apply a hydrated saved layout once (the bridge can answer after first render)
    useEffect(() => {
        if (!appliedSaved.current && savedMeta.ready && saved) {
            appliedSaved.current = true;
            setCam(saved.cam);
            setPos(sanePositions(saved.pos));
            setReviews(saved.reviews ?? {});
        }
    }, [savedMeta.ready, saved]);

    // initial view: fit the whole world to the screen, unless a saved view takes
    // over. Later resizes refit only while the user has not taken manual control.
    useEffect(() => {
        if (vp.w <= 0) return;
        if (!fitted.current) {
            fitted.current = true;
            if (!(savedMeta.ready && saved)) fitView();
        } else if (!viewTouched.current && !appliedSaved.current) {
            fitView();
        }
    }, [vp]);

    const persist = (c: Cam, p: Record<string, Pos>, r: Record<string, Mark>) => { if (savedMeta.ready) setSaved({ cam: c, pos: p, reviews: r }); };

    const evp = vp.w > 0 ? vp : { w: 390, h: 560 };
    const toWorld = (p: Pos, c: Cam): Pos => ({ x: c.x + (p.x - evp.w / 2) / c.z, y: c.y + (p.y - evp.h / 2) / c.z });

    function fitView() {
        const el = vpRef.current;
        const w = el ? el.clientWidth : vp.w, h = el ? el.clientHeight : vp.h;
        if (!w || !h) return;
        const c: Cam = { x: WORLD.w / 2, y: WORLD.h / 2, z: clampZ(Math.min(w / WORLD.w, h / WORLD.h) * 0.98) };
        // no-op guard: resize feedback (iframe height ping-pong) must not
        // turn into a setState/persist storm
        if (Math.abs(cam.x - c.x) < 0.5 && Math.abs(cam.y - c.y) < 0.5 && Math.abs(cam.z - c.z) < 0.001) return;
        setCam(c); persist(c, pos, reviews);
    }

    const focusCard = (id: string) => {
        viewTouched.current = true;
        setFocusedId(id);
        // Measure the real rendered card. CARD_SIZES holds estimates that run
        // tall (480 vs ~434 actual), which clipped the card top in focus mode.
        if (evp.w < 560) return; // phones read the card in the focus overlay below, not a shrunken canvas
        const el = vpRef.current?.querySelector(`[data-card="${id}"]`) as HTMLElement | null;
        const w = el?.offsetWidth || CARD_SIZES[id].w;
        const h = el?.offsetHeight || CARD_SIZES[id].h;
        const p = pos[id] ?? DEFAULT_POS[id];
        const ctr = { x: p.x + w / 2, y: p.y + h / 2 };
        const z = clampZ(Math.min(evp.w / (w + 80), evp.h / (h + 48), 1.2));
        const c = clampCam({ x: ctr.x, y: ctr.y, z });
        setCam(c); persist(c, pos, reviews);
    };

    // On phones Focus opens a full-screen readable overlay (scrollable, real
    // text size) instead of zooming the canvas. Lock page scroll while open.
    const overlayId = focusedId && evp.w < 560 ? focusedId : null;
    useEffect(() => {
        if (!overlayId) return;
        const prev = document.body.style.overflow;
        document.body.style.overflow = 'hidden';
        return () => { document.body.style.overflow = prev; };
    }, [overlayId]);

    const markSection = (id: string, m?: Mark) => {
        const r = { ...reviews };
        if (m) r[id] = m; else delete r[id];
        setReviews(r);
        persist(cam, pos, r);
    };

    const onPointerDown = (e: React.PointerEvent<HTMLDivElement>) => {
        const t = e.target as HTMLElement;
        const p = { x: e.clientX, y: e.clientY };
        pointers.current.set(e.pointerId, p);
        g.current.moved = false;
        if (pointers.current.size === 2) {
            const [a, b] = [...pointers.current.values()];
            g.current.mode = 'pinch';
            g.current.pinch = { d0: Math.hypot(a.x - b.x, a.y - b.y), z0: cam.z, cam0: cam, mid: { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 } };
            return;
        }
        if (t.closest('button') || t.closest('a') || t.closest('.rc-minimap') || t.closest('.rc-controls')) { g.current.mode = null; return; }
        const cardEl = t.closest('[data-card]') as HTMLElement | null;
        g.current.start = p; g.current.last = p;
        if (cardEl) {
            const id = cardEl.dataset.card!;
            g.current.mode = 'card'; g.current.cardId = id;
            const w = toWorld(p, cam);
            g.current.off = { x: w.x - pos[id].x, y: w.y - pos[id].y };
        } else {
            g.current.mode = 'pan';
        }
        setDragging(true);
    };

    const onPointerMove = (e: React.PointerEvent<HTMLDivElement>) => {
        if (!pointers.current.has(e.pointerId)) return;
        const p = { x: e.clientX, y: e.clientY };
        pointers.current.set(e.pointerId, p);
        const gg = g.current;
        if (!gg.moved && gg.mode && Math.hypot(p.x - gg.start.x, p.y - gg.start.y) > 6) {
            gg.moved = true;
            try { vpRef.current?.setPointerCapture(e.pointerId); } catch { /* noop */ }
        }
        if (gg.mode === 'pinch' && gg.pinch && pointers.current.size >= 2) {
            const [a, b] = [...pointers.current.values()];
            const d = Math.hypot(a.x - b.x, a.y - b.y);
            const mid = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 };
            const z = clampZ(gg.pinch.z0 * (d / (gg.pinch.d0 || 1)));
            const mw = toWorld(mid, gg.pinch.cam0);
            setCam(clampCam({ x: mw.x - (mid.x - evp.w / 2) / z, y: mw.y - (mid.y - evp.h / 2) / z, z }));
        } else if (gg.mode === 'card' && gg.cardId) {
            const w = toWorld(p, cam);
            const np = { ...pos, [gg.cardId]: { x: Math.round(w.x - gg.off.x), y: Math.round(w.y - gg.off.y) } };
            setPos(np);
        } else if (gg.mode === 'pan') {
            const dx = (p.x - gg.last.x) / cam.z, dy = (p.y - gg.last.y) / cam.z;
            gg.last = p;
            setCam(clampCam({ x: cam.x - dx, y: cam.y - dy, z: cam.z }));
        }
    };

    const endPointer = (e: React.PointerEvent<HTMLDivElement>) => {
        pointers.current.delete(e.pointerId);
        const gg = g.current;
        if (gg.mode === 'pinch' && pointers.current.size === 1) {
            const remaining = [...pointers.current.values()][0];
            gg.mode = 'pan'; gg.start = remaining; gg.last = remaining;
        } else if (pointers.current.size === 0) {
            if (gg.mode) { viewTouched.current = true; persist(cam, pos, reviews); }
            gg.mode = null; setDragging(false);
        }
    };

    const onClickCapture = (e: React.MouseEvent) => {
        if (g.current.moved) { e.stopPropagation(); e.preventDefault(); g.current.moved = false; }
    };


    const reviewedCount = SECTIONS.filter((s) => reviews[s.id] === 'reviewed').length;
    const flagCount = SECTIONS.filter((s) => reviews[s.id] === 'flag').length;

    const cards: Record<string, React.ReactNode> = {
        progress: <CardShell id="progress" tag="Center" title="Review progress" centerCard onFocus={focusCard}>
            <p>{reviewedCount} of {SECTIONS.length} sections reviewed{flagCount > 0 ? ` - ${flagCount} flagged for another look` : ''}.</p>
            <p className="rc-muted">Your marks are saved on this device only. Clearing all marks starts the pass over.</p>
        </CardShell>,
        howto: <CardShell id="howto" tag="Start here" title="How to review a section" onFocus={focusCard}>
            <ul>
                <li>Pick a card. Each is one piece of the repo, small enough for one sitting.</li>
                <li>Tap any file name - it opens that exact file at commit {COMMIT_SHORT}. Long lists unfold with "Show all files".</li>
                <li>Read, then come back and mark it: Reviewed, or Needs another look.</li>
                <li>"Not checked yet" lists what nobody has verified, so gaps stay visible.</li>
            </ul>
        </CardShell>,
        permanence: <CardShell id="permanence" tag="Permanence, honestly" title="What persists here" onFocus={focusCard}>
            <ul>
                <li>Card positions, your view, and your review marks are remembered in this browser's local storage on this static page.</li>
                <li>Not synced across devices; clearing browser storage resets them.</li>
                <li>Public repo content only, generated from irvan-putra/intodo @ {COMMIT_SHORT} on Nov 13, 2026; nothing is sent anywhere or published with this page, and nothing here changes the app or the repo.</li>
                <li>Marks made on the Instinct-hosted review page live in that browser's storage and cannot carry over here.</li>
                <li>Long-term ownership (export, backup) is an open question, not a claim.</li>
            </ul>
        </CardShell>,
        rule: <CardShell id="rule" tag="The honest rule" title="No hidden gaps" onFocus={focusCard}>
            <p>No card ever reads "this code is safe". Each card says exactly what it is, and what has not been checked stays visible in the same size next to it.</p>
        </CardShell>,
    };
    for (const s of SECTIONS) {
        cards[s.id] = <SectionCard key={s.id} s={s} mark={reviews[s.id]} expanded={expandedId === s.id} onToggleExpand={(id) => {
            const next = expandedId === id ? null : id;
            setExpandedId(next);
            // expanding the focused card changes its measured height; refocus
            // after the re-render so it still fits beside the Map chip strip
            if (next && focusedId === id) requestAnimationFrame(() => requestAnimationFrame(() => focusCard(id)));
        }} onMark={markSection} onFocus={focusCard} />;
    }

    return <>
        <div
            ref={vpRef}
            className={`rc-viewport${dragging ? ' rc-dragging' : ''}`}
            onPointerDown={onPointerDown}
            onPointerMove={onPointerMove}
            onPointerUp={endPointer}
            onPointerCancel={endPointer}
            onClickCapture={onClickCapture}
        >
            <div
                className="rc-world"
                style={{
                    width: WORLD.w, height: WORLD.h,
                    transform: `translate(${evp.w / 2 - cam.x * cam.z}px, ${evp.h / 2 - cam.y * cam.z}px) scale(${cam.z})`,
                }}
            >
                <svg className="rc-edges" width={WORLD.w} height={WORLD.h}>
                    {EDGES.map(([a, b]) => {
                        const ca = center(a, pos), cb = center(b, pos);
                        return <line key={`${a}-${b}`} x1={ca.x} y1={ca.y} x2={cb.x} y2={cb.y} stroke="var(--ds-hairline)" strokeWidth={2} />;
                    })}
                </svg>
                {Object.keys(cards).map((id) => (
                    <div key={id} style={{ position: 'absolute', left: pos[id].x, top: pos[id].y, width: CARD_SIZES[id].w, zIndex: expandedId === id ? 5 : undefined }}>
                        {cards[id]}
                    </div>
                ))}
            </div>
            {/* navigation chrome (zoom buttons, Fit, minimap) intentionally
                removed per owner request - pan and pinch remain; controls come
                back in a later iteration. */}
            <div className="rc-status" aria-live="polite">{savedMeta.message}</div>
        </div>
        {overlayId && cards[overlayId] ? (
            <div className="rc-overlay" role="dialog" aria-modal="true" aria-label={TITLES[overlayId]}>
                <div className="rc-overlay-head">
                    <button type="button" className="rc-overlay-back" onClick={() => setFocusedId(null)}>Back to canvas</button>
                </div>
                <div className="rc-overlay-body">{cards[overlayId]}</div>
            </div>
        ) : null}
    </>;
}

// Full-screen canvas, FreeForm-style: the canvas IS the page. No page
// framing, no square viewport; controls float over the canvas. Onboarding and
// the honesty/scope disclosures live on world cards (howto, permanence, rule,
// progress) so they pan and zoom with everything else.
export function App() {
    return <Canvas />;
}
