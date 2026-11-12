import React, { useEffect, useRef, useState } from 'react';
import { Caption, Closing, FileCard, Group, Header, Paragraph, Photos, Text, TextLink, useLocalState } from '@instinct/files';
import hero from './photos/review-concept-hero.png';

type Pos = { x: number; y: number };
type Cam = { x: number; y: number; z: number };
type Saved = { cam: Cam; pos: Record<string, Pos> };

const WORLD = { w: 1760, h: 1420 };
const ZMIN = 0.15, ZMAX = 2.5;

const CARD_SIZES: Record<string, { w: number; h: number }> = {
    change: { w: 380, h: 300 },
    permanence: { w: 380, h: 210 },
    identity: { w: 360, h: 210 },
    reviews: { w: 360, h: 200 },
    rule: { w: 360, h: 180 },
    source: { w: 360, h: 170 },
    gaps: { w: 380, h: 200 },
    property: { w: 320, h: 210 },
    mutation: { w: 320, h: 210 },
    fuzz: { w: 320, h: 210 },
    load: { w: 320, h: 210 },
    smoke: { w: 320, h: 210 },
};

const ICONS: Record<string, string> = {
    change: '\u{1F4DD}',
    permanence: '\u{1F4BE}',
    identity: '\u{1F511}',
    reviews: '\u{1F50D}',
    source: '\u{1F4E6}',
    rule: '\u{1F4CF}',
    gaps: '\u26A0\uFE0F',
    property: '\u{1F501}',
    mutation: '\u{1F9EC}',
    fuzz: '\u{1F3B2}',
    load: '\u{1F4C8}',
    smoke: '\u{1F4A8}',
};

const TITLES: Record<string, string> = {
    change: 'v0.4: narrow widget layout',
    permanence: 'What persists here',
    identity: 'Exact code identity',
    reviews: 'Reviews',
    source: 'The real code',
    rule: 'No hidden gaps',
    gaps: 'Not checked for v0.4',
    property: 'Property testing',
    mutation: 'Mutation testing',
    fuzz: 'Fuzz testing',
    load: 'Load testing',
    smoke: 'Smoke testing',
};

const DEFAULT_POS: Record<string, Pos> = {
    change: { x: 690, y: 430 },
    permanence: { x: 690, y: 60 },
    identity: { x: 160, y: 250 },
    reviews: { x: 1240, y: 250 },
    source: { x: 160, y: 620 },
    rule: { x: 1240, y: 620 },
    gaps: { x: 690, y: 820 },
    property: { x: 40, y: 1200 },
    mutation: { x: 400, y: 1200 },
    fuzz: { x: 760, y: 1200 },
    load: { x: 1120, y: 1200 },
    smoke: { x: 1420, y: 1200 },
};

const EDGES: [string, string][] = [
    ['permanence', 'change'],
    ['identity', 'change'],
    ['reviews', 'change'],
    ['source', 'change'],
    ['rule', 'change'],
    ['change', 'gaps'],
    ['change', 'property'],
    ['change', 'mutation'],
    ['change', 'fuzz'],
    ['change', 'load'],
    ['change', 'smoke'],
];

function center(id: string, pos: Record<string, Pos>): Pos {
    const s = CARD_SIZES[id];
    return { x: pos[id].x + s.w / 2, y: pos[id].y + s.h / 2 };
}

const clampZ = (z: number) => Math.min(ZMAX, Math.max(ZMIN, z));
const clampCam = (c: Cam): Cam => ({
    x: Math.min(WORLD.w + 120, Math.max(-120, c.x)),
    y: Math.min(WORLD.h + 120, Math.max(-120, c.y)),
    z: c.z,
});

function CardShell({ id, tag, title, centerCard, onFocus, children }: {
    id: string; tag: string; title: string; centerCard?: boolean; onFocus: (id: string) => void; children: React.ReactNode;
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
        {children}
    </div>;
}

function Canvas() {
    const [saved, setSaved, savedMeta] = useLocalState<Saved | null>('review-canvas.v1', null);
    const [cam, setCam] = useState<Cam>(saved?.cam ?? { x: WORLD.w / 2, y: WORLD.h / 2, z: 0.25 });
    const [pos, setPos] = useState<Record<string, Pos>>(saved?.pos ?? DEFAULT_POS);
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
        const ro = new ResizeObserver(() => setVp({ w: el.clientWidth, h: el.clientHeight }));
        ro.observe(el);
        setVp({ w: el.clientWidth, h: el.clientHeight });
        return () => ro.disconnect();
    }, []);

    // apply a hydrated saved layout once (the bridge can answer after first render)
    useEffect(() => {
        if (!appliedSaved.current && savedMeta.ready && saved) {
            appliedSaved.current = true;
            setCam(saved.cam);
            setPos(saved.pos);
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

    const persist = (c: Cam, p: Record<string, Pos>) => { if (savedMeta.ready) setSaved({ cam: c, pos: p }); };

    const evp = vp.w > 0 ? vp : { w: 390, h: 560 };
    const toWorld = (p: Pos, c: Cam): Pos => ({ x: c.x + (p.x - evp.w / 2) / c.z, y: c.y + (p.y - evp.h / 2) / c.z });

    function fitView() {
        const el = vpRef.current;
        const w = el ? el.clientWidth : vp.w, h = el ? el.clientHeight : vp.h;
        if (!w || !h) return;
        const c: Cam = { x: WORLD.w / 2, y: WORLD.h / 2, z: clampZ(Math.min(w / WORLD.w, h / WORLD.h) * 0.98) };
        setCam(c); persist(c, pos);
    }

    const zoomBy = (f: number) => {
        viewTouched.current = true;
        const c = clampCam({ ...cam, z: clampZ(cam.z * f) });
        setCam(c); persist(c, pos);
    };

    const focusCard = (id: string) => {
        viewTouched.current = true;
        const ctr = center(id, pos);
        const c = clampCam({ x: ctr.x, y: ctr.y, z: clampZ(Math.min(evp.w / (CARD_SIZES[id].w + 80), 1.2)) });
        setCam(c); persist(c, pos);
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
            if (gg.mode) { viewTouched.current = true; persist(cam, pos); }
            gg.mode = null; setDragging(false);
        }
    };

    const onClickCapture = (e: React.MouseEvent) => {
        if (g.current.moved) { e.stopPropagation(); e.preventDefault(); g.current.moved = false; }
    };

    const MINI_PAD = 4;
    const miniScale = (132 - MINI_PAD * 2) / WORLD.w;

    const cards: Record<string, React.ReactNode> = {
        change: <CardShell id="change" tag="Change card - example" title="v0.4: narrow widget layout" centerCard onFocus={focusCard}>
            <p>The widget can shrink to a narrow 2x1 cell and reminders stay readable.<span className="rc-real">real</span></p>
            <p>v0.4 is confirmed working on your phone - you verified it with a screenshot on Nov 12.<span className="rc-real">real</span></p>
            <p className="rc-muted">Reviewed by: no one yet. Proved: nothing.<span className="rc-mock">mock entries</span></p>
        </CardShell>,
        permanence: <CardShell id="permanence" tag="Permanence, honestly" title="What persists here" onFocus={focusCard}>
            <ul>
                <li>Card positions and your view are designed to be remembered on this device and browser. Implemented, not yet verified in your signed-in view.</li>
                <li>Not synced across devices; clearing browser storage resets it.</li>
                <li>Long-term ownership (export, backup) is an open question, not a claim.</li>
            </ul>
        </CardShell>,
        identity: <CardShell id="identity" tag="Unison idea" title="Exact code identity" onFocus={focusCard}>
            <p>Each card points at its change by content hash.<span className="rc-mock">mock 9f3c...a21e</span></p>
            <p className="rc-muted">A review attaches to that hash, never to "the latest code". Same hash means same code - it says nothing about whether the code is good.</p>
        </CardShell>,
        reviews: <CardShell id="reviews" tag="crev idea" title="Reviews" onFocus={focusCard}>
            <p>No one has reviewed v0.4.</p>
            <p className="rc-muted">Signed reviews from people you choose to trust would appear here, with what each one checked. A review is human judgment, not a scan.</p>
        </CardShell>,
        source: <CardShell id="source" tag="Today" title="The real code" onFocus={focusCard}>
            <p>The project source is public - the wall of code this canvas is meant to replace for review.</p>
            <p><TextLink href="https://github.com/irvan-putra/intodo">irvan-putra/intodo</TextLink></p>
        </CardShell>,
        rule: <CardShell id="rule" tag="The honest rule" title="No hidden gaps" onFocus={focusCard}>
            <p>No badge ever reads "app is secure". Each badge says exactly what was checked, and what was not checked stays visible in the same size next to it.</p>
        </CardShell>,
        gaps: <CardShell id="gaps" tag="Always visible" title="Not checked for v0.4" onFocus={focusCard}>
            <ul>
                <li>Independent check of the reported property tests (7 Java + 9 JS, run by the builders).</li>
                <li>Any proof - none exists.</li>
                <li>Real cross-client sync: v0.1 failed your DeltaChat / ArcaneChat test (Nov 12) - add, delete and done were dead because the page never loaded the webxdc API script. v0.2 fixed that load, and you confirmed real DeltaChat/ArcaneChat sync on-device Nov 12 (add, done, delete all synced). v0.2's remaining gap - vague chat notices - is fixed in v0.3 (indeta), which passes a 24-check two-peer simulator test; the v0.3 on-device retest is still pending. The v0.2 fix source is public in the widget repo (commit 715c86b).</li>
            </ul>
        </CardShell>,
        property: <CardShell id="property" tag="Evidence - reported" title="Property testing" onFocus={focusCard}>
            <p>Random operation sequences; merge invariants must hold (convergence, no lost items).</p>
            <p className="rc-muted">Reported run by the builders on the public merge source (commit 715c86b): 7 Java + 9 JS tests pass. Not independently verified here. Samples cases - weaker than a proof.</p>
        </CardShell>,
        mutation: <CardShell id="mutation" tag="Planned evidence - not run" title="Mutation testing" onFocus={focusCard}>
            <p>Deliberately break the merge code; the tests must catch each break.</p>
            <p className="rc-muted">Shows whether the tests are effective, not whether the code is secure. Status: not run.<span className="rc-mock">plan</span></p>
        </CardShell>,
        fuzz: <CardShell id="fuzz" tag="Planned evidence - not run" title="Fuzz testing" onFocus={focusCard}>
            <p>Random and malformed input at the parsers and the sync protocol boundary.</p>
            <p className="rc-muted">Scope: crash and rejection behavior, not full protocol correctness. Status: not run.<span className="rc-mock">plan</span></p>
        </CardShell>,
        load: <CardShell id="load" tag="Planned evidence - not run" title="Load testing" onFocus={focusCard}>
            <p>Realistic multi-device operation volume and resource use.</p>
            <p className="rc-muted">Local and simulated only - no hammering of external services. Status: not run.<span className="rc-mock">plan</span></p>
        </CardShell>,
        smoke: <CardShell id="smoke" tag="Planned evidence - not run" title="Smoke testing" onFocus={focusCard}>
            <p>A short "does it basically work" pass per build: app starts, list loads, one add, one sync.</p>
            <p className="rc-muted">Fast signal, shallow. Status: not run.<span className="rc-mock">plan</span></p>
        </CardShell>,
    };

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
                    <div key={id} style={{ position: 'absolute', left: pos[id].x, top: pos[id].y, width: CARD_SIZES[id].w }}>
                        {cards[id]}
                    </div>
                ))}
            </div>
            <div className="rc-controls">
                <button type="button" aria-label="Zoom in" onClick={() => zoomBy(1.35)}>+</button>
                <button type="button" aria-label="Zoom out" onClick={() => zoomBy(1 / 1.35)}>-</button>
                <button type="button" className="rc-fit" onClick={() => { viewTouched.current = true; fitView(); }}>Fit</button>
            </div>
            <div
                className="rc-minimap"
                style={{ width: 132, height: Math.round(WORLD.h * miniScale) + MINI_PAD * 2 }}
                onPointerDown={(e) => {
                    e.stopPropagation();
                    viewTouched.current = true;
                    const r = (e.currentTarget as HTMLDivElement).getBoundingClientRect();
                    const c = clampCam({ x: (e.clientX - r.left - MINI_PAD) / miniScale, y: (e.clientY - r.top - MINI_PAD) / miniScale, z: cam.z });
                    setCam(c); persist(c, pos);
                }}
            >
                {Object.keys(CARD_SIZES).map((id) => (
                    <div key={id} className={`rc-minicard${id === 'change' ? ' rc-center' : ''}`} role="img" aria-label={TITLES[id]} title={TITLES[id]} style={{
                        left: MINI_PAD + pos[id].x * miniScale, top: MINI_PAD + pos[id].y * miniScale,
                        width: Math.max(14, CARD_SIZES[id].w * miniScale), height: Math.max(13, CARD_SIZES[id].h * miniScale),
                    }}>
                        <span className="rc-miniicon" aria-hidden="true">{ICONS[id]}</span>
                    </div>
                ))}
                <div className="rc-miniview" style={{
                    left: MINI_PAD + (cam.x - evp.w / (2 * cam.z)) * miniScale, top: MINI_PAD + (cam.y - evp.h / (2 * cam.z)) * miniScale,
                    width: (evp.w / cam.z) * miniScale, height: (evp.h / cam.z) * miniScale,
                }} />
            </div>
        </div>
        <Caption>Drag the background to pan, drag a card to move it, pinch or use + and - to zoom, tap a card's Focus to read it. The canvas is bounded, not infinite. {savedMeta.message}</Caption>
    </>;
}

export function App() {
    return <FileCard>
        <Header
            title="Review each change from your phone"
            fact="Interactive design prototype"
            intro="The best ideas from crev, Unison, Haskell and Lean, merged into one review surface for the intodo project. Cards live on a canvas, not in pages or an endless feed: they stay where you put them, and the layout is designed to persist on this device - implemented, not yet verified in your own signed-in view."
        />
        <Photos label="The review canvas idea" title="Review canvas concept" items={[{ src: hero, alt: 'Illustration of a phone showing a change card with review, test and proof markers' }]} />
        <Caption>Illustrative concept art, not a screenshot of a working feature.</Caption>

        <Group label="The idea" heading>
            <Text>
                <Paragraph>Every change becomes a card you can read in under a minute: what changed, which exact code it is, who reviewed it, and what was tested or proved. Cards connect spatially to their evidence, and what has not been checked stays visible in the same size type as what has.</Paragraph>
                <Paragraph>The example below uses the real v0.4 narrow-layout change. "Real" chips mark grounded facts; "mock" chips mark illustrative entries. This is a working interactive mockup, not a production review system - nothing is adopted or built into the app.</Paragraph>
            </Text>
        </Group>

        <Group label="Try it" heading="The review canvas">
            <Canvas />
        </Group>

        <Closing>Interactive design prototype, Nov 12, 2026. Adopting this as the project standard is your call - nothing here changes the app or the repo.</Closing>
    </FileCard>;
}
