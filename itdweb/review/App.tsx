import React, { useEffect, useRef, useState } from 'react';
import { TextLink, useLocalState } from './files-shim';
import { COMMIT, COMMIT_SHORT, SECTIONS, Section } from '../src/sections';

type Pos = { x: number; y: number };
type Cam = { x: number; y: number; z: number };
type Mark = 'reviewed' | 'flag';
type Saved = { cam: Cam; pos: Record<string, Pos>; reviews?: Record<string, Mark> };

const WORLD = { w: 2160, h: 2560 };
const ZMIN = 0.07, ZMAX = 2.5;
// Zoom bands, C4-style progressive disclosure (c4model.com):
//   z < SYS_Z    system context: people + the intodo system + external systems
//   z < GROUP_Z  container level: the repo's runnable units + code-evidenced
//                relationships; tap a container to open its sections
//   z < LO_Z     section level: compact cards (icon + title + mark) grouped
//                under their C4 container labels, no per-file detail;
//                tap a card to focus it
//   otherwise    detail level: the full review cards, unchanged
const SYS_Z = 0.11;
//   otherwise    detail level: the full review cards, unchanged
const GROUP_Z = 0.28;
const LO_Z = 0.6;

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
    'review-src':    { x: COL_X[4], y: ROW_Y[3] },
};


// C4 model, grounded in the public repo at the pinned snapshot (see
// sections.ts COMMIT; each relationship names its evidence file). Runnable
// containers: itdapk, itdxdc, itdweb and the feed encryption CLI. Tests
// attach to the containers they verify; build/CI and repo docs stay one
// honestly-labeled supporting group; the developer and the draft GitHub
// Actions workflows appear as people/external systems, not runtime boxes.
// Solid lines are observed flows or evidenced manual steps; dashed lines
// are designed but not active (draft workflows) or design similarity with
// no deployed runtime link.
//
// The container and system bands are true semantic zoom: instead of
// shrinking a fixed world-space diagram, each band re-lays-out against the
// CURRENTLY VISIBLE world rect, so node type stays a constant on-screen
// size at every zoom level, and edges route around unrelated boxes.
type C4Node = { id: string; label: string; sub: string; kind: 'container' | 'support' | 'external' | 'person' | 'system'; members?: string[]; attached?: string[]; frac: { fx: number; fy: number } };
const C4_CONTAINERS: C4Node[] = [
    { id: 'c-app', label: 'itdapk - Android app', sub: 'Java/Kotlin + Chaquopy', kind: 'container', frac: { fx: 0.5, fy: 0.08 },
      members: ['list-ui', 'feed-sync', 'self-update', 'native-canvas', 'merge-core', 'bot-app', 'bot-runtime', 'bot-delivery', 'witness'],
      attached: ['tests-android', 'tests-bot'] },
    { id: 'c-xdc', label: 'itdxdc - webxdc app', sub: 'JavaScript in DeltaChat', kind: 'container', frac: { fx: 0.22, fy: 0.44 }, members: ['xdc-app', 'xdc-canvas'], attached: ['tests-xdc'] },
    { id: 'c-web', label: 'itdweb - review page', sub: 'static GitHub Pages site', kind: 'container', frac: { fx: 0.72, fy: 0.48 },
      members: ['this-page', 'review-src'], attached: ['howto', 'rule', 'permanence', 'progress'] },
    // The feed encryption tool is a runnable container in C4 terms (a CLI
    // application): it reads feed JSON on stdin and prints the IFW1 payload.
    // It does NOT sign and does NOT upload - publishing the feed page is a
    // manual handoff, so there is no edge from it to Instinct Files.
    { id: 'c-feedtool', label: 'Feed encryption tool', sub: 'Python CLI, run by hand', kind: 'container', frac: { fx: 0.3, fy: 0.72 }, members: ['feed-tool'] },
    // Tests attach to the containers they verify; build/CI and repo docs stay
    // one honestly-labeled supporting group, not fake runtime architecture.
    { id: 'c-devsupport', label: 'Build, CI & repo docs', sub: 'supporting - not runtime', kind: 'support', frac: { fx: 0.32, fy: 0.85 },
      members: ['build-ci', 'assets-docs'] },
];
const C4_EXTERNALS: C4Node[] = [
    { id: 'x-files', label: 'Instinct Files', sub: 'encrypted feed page', kind: 'external', frac: { fx: 0.16, fy: 0.24 } },
    { id: 'x-github', label: 'GitHub', sub: 'releases + Pages', kind: 'external', frac: { fx: 0.84, fy: 0.24 } },
    { id: 'x-dc', label: 'DeltaChat network', sub: 'messages + webxdc', kind: 'external', frac: { fx: 0.72, fy: 0.78 } },
    // Turso deliberately omitted per owner request (13 Nov): the repo's
    // turso_store.py adapter is designed, not deployed, and is out of the diagram
];
// The developer appears on the container view as the person who builds and
// tests the apps and runs the feed tool - an ordinary external role only.
const C4_PEOPLE: C4Node[] = [
    { id: 'p-dev', label: 'Developer', sub: 'the owner, building + publishing', kind: 'person', frac: { fx: 0.08, fy: 0.62 } },
];
const CONTAINER_NODES: C4Node[] = [...C4_CONTAINERS, ...C4_PEOPLE, ...C4_EXTERNALS];

// Section-band layout: computed container-owned flow. The free-form pos grid
// mixes containers within rows, so in the section band chips are laid out
// fresh under their container label in reading order; the detail band keeps
// the saved/default positions.
const LO_COL_W = 400, LO_ROW_H = 130, LO_LABEL_H = 60, LO_GAP = 40;
const LO_LAYOUT: Record<string, { x: number; y: number }> = {};
const LO_LABELS: Record<string, { x: number; y: number }> = {};
{
    let y = 40;
    for (const g of C4_CONTAINERS) {
        LO_LABELS[g.id] = { x: 20, y };
        y += LO_LABEL_H;
        const all = [...(g.members ?? []), ...(g.attached ?? [])];
        all.forEach((m, i) => {
            LO_LAYOUT[m] = { x: 20 + (i % 5) * LO_COL_W, y: y + Math.floor(i / 5) * LO_ROW_H };
        });
        y += Math.ceil(all.length / 5) * LO_ROW_H + LO_GAP;
    }
}
type C4Rel = { a: string; b: string; label: string; dashed?: boolean; side?: 1 | -1 };
const C4_RELS: C4Rel[] = [
    { a: 'c-app', b: 'x-files', label: 'encrypted feed (HTTPS)' },                      // app/.../SyncEngine.java
    { a: 'c-app', b: 'x-github', label: 'update check + APK download' },                // app/.../UpdateCheck.java
    { a: 'c-app', b: 'x-dc', label: 'bot bridge + webxdc (RPC)' },                      // app/src/main/python/bot.py, bot_runtime.py, delivery_bot.py
    { a: 'c-xdc', b: 'x-dc', label: 'webxdc updates' },                                 // webxdc/js/app.js
    { a: 'c-web', b: 'x-github', label: 'links at pinned commit' },                     // itdweb/src/sections.ts
    { a: 'c-app', b: 'c-xdc', label: 'same merge algorithm, no runtime link', dashed: true }, // app/.../merge/MergeDoc.java vs webxdc/js/mergedoc.js
    { a: 'p-dev', b: 'c-app', label: 'builds + tests (Gradle)' },              // gradlew, app/build.gradle, app/src/test
    { a: 'p-dev', b: 'c-feedtool', label: 'feed JSON in, IFW1 payload out' },  // tools/encrypt_feed.py (stdin -> stdout, print-only)
];
const SYS_NODES: C4Node[] = [
    { id: 'p-owner', label: 'Irvan', sub: 'owner', kind: 'person', frac: { fx: 0.22, fy: 0.2 } },
    { id: 'p-members', label: 'Group members', sub: 'DeltaChat chat', kind: 'person', frac: { fx: 0.78, fy: 0.2 } },
    // Same human as the owner, in the developer role: builds, tests and
    // publishes. Modeled separately because the owner asked for developers as
    // users of the build/publish services, not as runtime architecture.
    { id: 'p-dev', label: 'Developer', sub: 'the owner, building + publishing', kind: 'person', frac: { fx: 0.5, fy: 0.06 } },
    { id: 's-intodo', label: 'intodo', sub: 'reminders + lists', kind: 'system', frac: { fx: 0.5, fy: 0.44 } },
    { id: 'x-gha', label: 'GitHub Actions', sub: 'draft workflows, manual trigger', kind: 'external', frac: { fx: 0.88, fy: 0.44 } },
    { id: 'x-files', label: 'Instinct Files', sub: 'feed page', kind: 'external', frac: { fx: 0.18, fy: 0.7 } },
    { id: 'x-github', label: 'GitHub', sub: 'releases + Pages', kind: 'external', frac: { fx: 0.82, fy: 0.7 } },
    { id: 'x-dc', label: 'DeltaChat network', sub: 'messages + webxdc', kind: 'external', frac: { fx: 0.5, fy: 0.86 } },
];
const SYS_RELS: C4Rel[] = [
    { a: 'p-owner', b: 's-intodo', label: 'uses + reviews' },
    { a: 'p-members', b: 's-intodo', label: 'edit shared list' },
    { a: 's-intodo', b: 'x-files', label: 'feed page' },
    { a: 's-intodo', b: 'x-github', label: 'source, releases, pages' },
    { a: 's-intodo', b: 'x-dc', label: 'bot + webxdc' },
    { a: 'p-dev', b: 's-intodo', label: 'builds + tests (Gradle)' },
    // Both workflows are REVIEW DRAFTS, manual workflow_dispatch only: no
    // automatic CI runs on push (grounded in .github/workflows/*.yml).
    { a: 'p-dev', b: 'x-gha', label: 'triggers release + render-check (draft)', dashed: true },
    { a: 'x-gha', b: 'x-github', label: 'stamps version, signs + publishes APK (draft)', dashed: true },
    // encrypt_feed.py only prints ciphertext; publishing the feed page is a
    // manual human step (rebuild + publish the File), not an integration.
    { a: 'p-dev', b: 'x-files', label: 'encrypts feed, publishes page by hand' },
];
type Rect = { x: number; y: number; w: number; h: number };
type GroupNode = { g: C4Node; x: number; y: number; hw: number; hh: number };
// Screen-constant metrics: every size is device px, divided by the zoom to
// get world units - text never shrinks with distance, the layout breathes.
const D_LABEL = 13.5, D_SUB = 10, D_PADX = 14, D_PADY = 9, D_GAP = 3;
function diagramLayout(defs: C4Node[], rect: Rect, z: number, measured: Record<string, { w: number; h: number }>): GroupNode[] {
    const nodes: GroupNode[] = defs.map((g) => {
        // labels wrap: boxes stay narrow enough to leave routable corridors
        const MAXW = 150;
        const labelCpl = Math.max(6, Math.floor((MAXW - 2 * D_PADX) / (D_LABEL * 0.62)));
        const subCpl = Math.max(8, Math.floor((MAXW - 2 * D_PADX) / (D_SUB * 0.58)));
        const labelLines = Math.ceil(g.label.length / labelCpl);
        const subLines = Math.ceil(g.sub.length / subCpl);
        let wPx = MAXW;
        let hPx = 2 * D_PADY + labelLines * D_LABEL * 1.2 + D_GAP + subLines * D_SUB * 1.3;
        if (g.members) hPx += D_GAP + D_SUB * 1.3;
        const m = measured[g.id];
        if (m) { wPx = m.w; hPx = m.h; }
        return { g, x: rect.x + g.frac.fx * rect.w, y: rect.y + g.frac.fy * rect.h, hw: wPx / 2 / z, hh: hPx / 2 / z };
    });
    // fractions are chosen collision-free, but narrow viewports compress x;
    // push overlapping pairs apart AND clamp inside the visible rect, in the
    // same loop, so clamping cannot re-create overlaps
    const clampN = (n: GroupNode) => {
        n.x = Math.min(Math.max(n.x, rect.x + n.hw + 8 / z), rect.x + rect.w - n.hw - 8 / z);
        n.y = Math.min(Math.max(n.y, rect.y + n.hh + 8 / z), rect.y + rect.h - n.hh - 8 / z);
    };
    for (let iter = 0; iter < 60; iter++) {
        let moved = false;
        for (let i = 0; i < nodes.length; i++) for (let j = i + 1; j < nodes.length; j++) {
            const a = nodes[i], b = nodes[j];
            const ox = a.hw + b.hw + 20 / z - Math.abs(a.x - b.x);
            const oy = a.hh + b.hh + 16 / z - Math.abs(a.y - b.y);
            if (ox > 0 && oy > 0) {
                moved = true;
                if (ox < oy) { const d = (a.x <= b.x ? -1 : 1) * (ox / 2); a.x += d; b.x -= d; }
                else { const d = (a.y <= b.y ? -1 : 1) * (oy / 2); a.y += d; b.y -= d; }
                clampN(a); clampN(b);
            }
        }
        if (!moved) break;
    }
    for (const n of nodes) clampN(n);
    return nodes;
}
// Edge routing: straight border-to-border where clear; where a segment
// crosses an unrelated box, insert a perpendicular detour waypoint beyond
// that box and re-check. Badge goes on the longest unobstructed segment,
// beside the line, flipping sides if it would sit on a box.
type RelGeom = { key: string; pts: Pos[]; bx: number; by: number; r: number; dashed?: boolean };
function segHitsNode(p: Pos, q: Pos, n: GroupNode, m: number): boolean {
    const x0 = n.x - n.hw - m, x1 = n.x + n.hw + m, y0 = n.y - n.hh - m, y1 = n.y + n.hh + m;
    // Liang-Barsky segment vs AABB
    const dx = q.x - p.x, dy = q.y - p.y;
    let t0 = 0, t1 = 1;
    const clip = (d: number, o: number) => {
        if (d === 0) return o >= 0;
        const t = o / d;
        if (d < 0) { if (t > t1) return false; if (t > t0) t0 = t; }
        else { if (t < t0) return false; if (t < t1) t1 = t; }
        return true;
    };
    return clip(-dx, p.x - x0) && clip(dx, x1 - p.x) && clip(-dy, p.y - y0) && clip(dy, y1 - p.y) && t0 < t1;
}
function routeRels(rels: C4Rel[], nodes: GroupNode[], z: number, rect: Rect): RelGeom[] {
    const M = 4 / z, CELL = 4 / z, RB = 11 / z;
    // connected pairs need room for a badge on the segment between them:
    // push the far node out until the border-to-border gap fits one
    for (const rel of rels) {
        const na = nodes.find((n) => n.g.id === rel.a)!, nb = nodes.find((n) => n.g.id === rel.b)!;
        const dx = nb.x - na.x, dy = nb.y - na.y, len = Math.hypot(dx, dy) || 1;
        const ux = dx / len, uy = dy / len;
        const reach = (n: GroupNode) => Math.min(n.hw / (Math.abs(ux) || 1e-6), n.hh / (Math.abs(uy) || 1e-6));
        const gap = len - reach(na) - reach(nb);
        const need = 2 * RB + 30 / z;
        if (gap < need) {
            const push = (need - gap) / 2;
            na.x -= ux * push; na.y -= uy * push;
            nb.x += ux * push; nb.y += uy * push;
            for (const n of [na, nb]) {
                n.x = Math.min(Math.max(n.x, rect.x + n.hw + 8 / z), rect.x + rect.w - n.hw - 8 / z);
                n.y = Math.min(Math.max(n.y, rect.y + n.hh + 8 / z), rect.y + rect.h - n.hh - 8 / z);
            }
        }
    }
    const border = (n: GroupNode, ux: number, uy: number): Pos => {
        const t = Math.min(n.hw / (Math.abs(ux) || 1e-6), n.hh / (Math.abs(uy) || 1e-6));
        return { x: n.x + ux * t, y: n.y + uy * t };
    };
    // grid bounds cover every node plus breathing room
    let gx0 = Infinity, gy0 = Infinity, gx1 = -Infinity, gy1 = -Infinity;
    for (const n of nodes) {
        gx0 = Math.min(gx0, n.x - n.hw - 60 / z); gy0 = Math.min(gy0, n.y - n.hh - 60 / z);
        gx1 = Math.max(gx1, n.x + n.hw + 60 / z); gy1 = Math.max(gy1, n.y + n.hh + 60 / z);
    }
    // keep routes on screen: never path outside the visible band rect
    gx0 = Math.max(gx0, rect.x + 8 / z); gy0 = Math.max(gy0, rect.y + 8 / z);
    gx1 = Math.min(gx1, rect.x + rect.w - 8 / z); gy1 = Math.min(gy1, rect.y + rect.h - 8 / z);
    const gw = Math.max(4, Math.ceil((gx1 - gx0) / CELL)), gh = Math.max(4, Math.ceil((gy1 - gy0) / CELL));
    const cellOf = (x: number, y: number): [number, number] => [
        Math.min(gw - 1, Math.max(0, Math.round((x - gx0) / CELL))),
        Math.min(gh - 1, Math.max(0, Math.round((y - gy0) / CELL))),
    ];
    const cellXY = (cx: number, cy: number): Pos => ({ x: gx0 + cx * CELL, y: gy0 + cy * CELL });
    const inBox = (x: number, y: number, n: GroupNode, m: number) =>
        Math.abs(x - n.x) < n.hw + m && Math.abs(y - n.y) < n.hh + m;
    // pass 1: route every edge
    const routed = rels.map((rel) => {
        const na = nodes.find((n) => n.g.id === rel.a)!, nb = nodes.find((n) => n.g.id === rel.b)!;
        const dx = nb.x - na.x, dy = nb.y - na.y;
        const len = Math.hypot(dx, dy) || 1;
        let pts: Pos[] = [border(na, dx / len, dy / len), border(nb, -dx / len, -dy / len)];
        const others = nodes.filter((n) => n !== na && n !== nb);
        const straightBlocked = others.some((n) => segHitsNode(pts[0], pts[1], n, M));
        let routed = !straightBlocked;
        for (let attempt = 0; attempt < 2 && !routed; attempt++) {
            const mAtt = attempt === 0 ? M : 2 / z;
            // A* over a coarse grid; cells inside a non-endpoint box are walls
            const [scx, scy] = cellOf(pts[0].x, pts[0].y), [tcx, tcy] = cellOf(pts[1].x, pts[1].y);
            const blocked = (cx: number, cy: number) => {
                if ((cx === scx && cy === scy) || (cx === tcx && cy === tcy)) return false;
                const { x, y } = cellXY(cx, cy);
                return others.some((n) => inBox(x, y, n, mAtt));
            };
            const W = gw, H = gh;
            const idx = (cx: number, cy: number) => cy * W + cx;
            const gcost = new Float64Array(W * H).fill(Infinity);
            const from = new Int32Array(W * H).fill(-1);
            const open: [number, number][] = [[0, idx(scx, scy)]];
            gcost[idx(scx, scy)] = 0;
            const h = (cx: number, cy: number) => Math.abs(cx - tcx) + Math.abs(cy - tcy);
            let found = false, guard = W * H * 8;
            while (open.length && guard-- > 0) {
                let bi = 0;
                for (let k = 1; k < open.length; k++) if (open[k][0] < open[bi][0]) bi = k;
                const [, cur] = open.splice(bi, 1)[0];
                const cx = cur % W, cy = (cur - cx) / W;
                if (cx === tcx && cy === tcy) { found = true; break; }
                const dirs = [[1, 0], [-1, 0], [0, 1], [0, -1]];
                for (const [ox, oy] of dirs) {
                    const nx = cx + ox, ny = cy + oy;
                    if (nx < 0 || ny < 0 || nx >= W || ny >= H || blocked(nx, ny)) continue;
                    const pi = from[cur];
                    const turn = pi >= 0 && ((pi % W) !== cx && ((pi - (pi % W)) / W) !== cy) && ((pi % W) !== nx || ((pi - (pi % W)) / W) !== ny) ? 0.6 : 0;
                    const ng = gcost[cur] + 1 + turn;
                    if (ng < gcost[idx(nx, ny)]) {
                        gcost[idx(nx, ny)] = ng; from[idx(nx, ny)] = cur;
                        open.push([ng + h(nx, ny), idx(nx, ny)]);
                    }
                }
            }
            if (found) {
                const cells: [number, number][] = [];
                let cur = idx(tcx, tcy);
                while (cur >= 0) { cells.push([cur % W, (cur - (cur % W)) / W]); cur = from[cur]; }
                cells.reverse();
                pts = cells.map(([cx, cy]) => cellXY(cx, cy));
                pts[0] = border(na, pts[1].x - na.x, pts[1].y - na.y);
                pts[pts.length - 1] = border(nb, pts[pts.length - 2].x - nb.x, pts[pts.length - 2].y - nb.y);
                // string pulling: keep only waypoints needed to stay clear
                const pulled: Pos[] = [pts[0]];
                let a = 0;
                while (a < pts.length - 1) {
                    let b = pts.length - 1;
                    while (b > a + 1 && others.some((n) => segHitsNode(pts[a], pts[b], n, 3 / z))) b--;
                    pulled.push(pts[b]); a = b;
                }
                pts = pulled;
                routed = true;
            }
        }
        return { rel, na, nb, pts };
    });
    const segDist = (x: number, y: number, p: Pos, q: Pos): number => {
        const vx = q.x - p.x, vy = q.y - p.y;
        const L2 = vx * vx + vy * vy || 1;
        const t = Math.max(0, Math.min(1, ((x - p.x) * vx + (y - p.y) * vy) / L2));
        return Math.hypot(x - (p.x + vx * t), y - (p.y + vy * t));
    };
    // pass 2: badges - candidates along the route by arc length, avoiding
    // boxes, other badges, and every OTHER edge's path
    const placed: Pos[] = [];
    return routed.map(({ rel, na, nb, pts }, i) => {
        const seglens: number[] = [];
        let total = 0;
        for (let si = 0; si < pts.length - 1; si++) {
            const L = Math.hypot(pts[si + 1].x - pts[si].x, pts[si + 1].y - pts[si].y);
            seglens.push(L); total += L;
        }
        const arcAt = (t: number): { x: number; y: number; ux: number; uy: number } => {
            let d = t * total;
            for (let si = 0; si < seglens.length; si++) {
                if (d <= seglens[si] || si === seglens.length - 1) {
                    const L = seglens[si] || 1;
                    const ux = (pts[si + 1].x - pts[si].x) / L, uy = (pts[si + 1].y - pts[si].y) / L;
                    return { x: pts[si].x + ux * d, y: pts[si].y + uy * d, ux, uy };
                }
                d -= seglens[si];
            }
            return { x: pts[0].x, y: pts[0].y, ux: 1, uy: 0 };
        };
        const side = rel.side ?? (i % 2 === 0 ? 1 : -1);
        const badgeHits = (x: number, y: number) =>
            nodes.some((n) => inBox(x, y, n, RB + 2 / z)) ||
            placed.some((b) => Math.hypot(x - b.x, y - b.y) < 2 * RB + 4 / z) ||
            routed.some((r2, j) => j !== i && r2.pts.some((p2, si) => si < r2.pts.length - 1 && segDist(x, y, p2, r2.pts[si + 1]) < 2 * RB + 2 / z)) ||
            x - RB < rect.x + 6 / z || x + RB > rect.x + rect.w - 6 / z ||
            y - RB < rect.y + 6 / z || y + RB > rect.y + rect.h - 6 / z;
        const mid0 = arcAt(0.5);
        let bx = mid0.x, by = mid0.y;
        const cands: [number, number][] = [];
        for (const t of [0.5, 0.4, 0.6, 0.3, 0.7, 0.22, 0.78, 0.15, 0.85, 0.08, 0.92]) {
            const a = arcAt(t);
            cands.push([a.x - a.uy * side * (RB + 4 / z), a.y + a.ux * side * (RB + 4 / z)]);
            cands.push([a.x + a.uy * side * (RB + 4 / z), a.y - a.ux * side * (RB + 4 / z)]);
        }
        cands.push([mid0.x, mid0.y]);
        let free = false;
        for (const [cx, cy] of cands) if (!badgeHits(cx, cy)) { bx = cx; by = cy; free = true; break; }
        if (!free) {
            // crowded corner: take the candidate with the most clearance
            let bestScore = -Infinity;
            for (const [cx, cy] of cands) {
                let score = Infinity;
                for (const n of nodes) {
                    const gx = Math.max(Math.abs(cx - n.x) - n.hw, 0), gy = Math.max(Math.abs(cy - n.y) - n.hh, 0);
                    score = Math.min(score, Math.hypot(gx, gy));
                }
                for (const b of placed) score = Math.min(score, Math.hypot(cx - b.x, cy - b.y));
                if (score > bestScore) { bestScore = score; bx = cx; by = cy; }
            }
        }
        placed.push({ x: bx, y: by });
        const tuck = (n: GroupNode, at: number, from: number) => {
            const ux = n.x - pts[from].x, uy = n.y - pts[from].y;
            const L = Math.hypot(ux, uy) || 1;
            pts[at] = { x: pts[at].x + (ux / L) * 2.5 / z, y: pts[at].y + (uy / L) * 2.5 / z };
        };
        tuck(na, 0, 1);
        tuck(nb, pts.length - 1, pts.length - 2);
        return { key: `${rel.a}-${rel.b}`, pts, bx, by, r: RB, dashed: rel.dashed };
    });
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
        <div className="rc-cardhead" onClick={() => onFocus(id)} title="Focus this section">
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
    const [focusedId, setFocusedId] = useState<string | null>(null);
    const [sheetGroup, setSheetGroup] = useState<string | null>(null);
    const [legendOpen, setLegendOpen] = useState(false);
    const [measuredNodes, setMeasuredNodes] = useState<Record<string, { w: number; h: number }>>({});
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

    // Context level -> section level: zoom the camera to a group's bounds so
    // its member concept cards fill the view (lands inside the section band)
    const focusGroup = (gid: string) => {
        viewTouched.current = true;
        const g = C4_CONTAINERS.find((x) => x.id === gid)!;
        // phones can't fit a wide container's cards side by side readably,
        // so the container opens as a list sheet of its sections instead
        if (evp.w < 560) { setSheetGroup(gid); return; }
        let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
        for (const m of [...g.members, ...(g.attached ?? [])]) {
            const p = pos[m] ?? DEFAULT_POS[m];
            const s = CARD_SIZES[m] ?? { w: 360, h: 480 };
            minX = Math.min(minX, p.x); minY = Math.min(minY, p.y);
            maxX = Math.max(maxX, p.x + s.w); maxY = Math.max(maxY, p.y + s.h);
        }
        const bw = maxX - minX, bh = maxY - minY;
        // fit the group's bounds; when it fits at a readable zoom, land in
        // the detail band so its sections show their full cards
        const zFit = Math.min(evp.w / (bw + 140), evp.h / (bh + 140), 0.75);
        const z = clampZ(Math.max(zFit, GROUP_Z + 0.04));
        const c = clampCam({ x: minX + bw / 2, y: minY + bh / 2, z });
        setCam(c); persist(c, pos, reviews);
    };

    // On phones Focus opens a full-screen readable overlay (scrollable, real
    // text size) instead of zooming the canvas. Lock page scroll while open.
    const overlayId = focusedId && evp.w < 560 ? focusedId : null;
    const sheetOpen = sheetGroup !== null && evp.w < 560;
    useEffect(() => {
        if (!overlayId && !sheetOpen) return;
        const prev = document.body.style.overflow;
        document.body.style.overflow = 'hidden';
        return () => { document.body.style.overflow = prev; };
    }, [overlayId, sheetOpen]);

    const band = cam.z < SYS_Z ? 'sys' : cam.z < GROUP_Z ? 'grp' : cam.z < LO_Z ? 'lo' : 'detail';

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
        if (t.closest('button') || t.closest('a')) { g.current.mode = null; return; }
        // cards are fixed in place per owner request: dragging anywhere pans
        // the canvas; card positions only change through code, never by hand
        g.current.start = p; g.current.last = p;
        g.current.mode = 'pan';
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
        } else if (gg.mode === 'pan') {
            const dx = (p.x - gg.last.x) / cam.z, dy = (p.y - gg.last.y) / cam.z;
            gg.last = p;
            setCam(clampCam({ x: cam.x - dx, y: cam.y - dy, z: cam.z }));
        }
    };

    // wheel zoom toward the cursor, endless-paper style: trackpad pinch
    // arrives as ctrl+wheel, plain wheel zooms too; zoom is continuous
    // instead of hidden behind buttons, per the owner's reference
    const onWheel = (e: React.WheelEvent<HTMLDivElement>) => {
        viewTouched.current = true;
        const f = Math.exp(-e.deltaY * (e.ctrlKey ? 0.008 : 0.0015));
        const z = clampZ(cam.z * f);
        const p = { x: e.clientX, y: e.clientY };
        const mw = toWorld(p, cam);
        const c = clampCam({ x: mw.x - (p.x - evp.w / 2) / z, y: mw.y - (p.y - evp.h / 2) / z, z });
        setCam(c); persist(c, pos, reviews);
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
    useEffect(() => {
        if (band !== 'grp' && band !== 'sys') return;
        const sel = band === 'grp' ? '.rc-groups .rc-grpnode' : '.rc-sysnodes .rc-grpnode';
        let changed = false;
        const next = { ...measuredNodes };
        document.querySelectorAll(sel).forEach((el) => {
            const id = (el as HTMLElement).dataset.nid;
            if (!id) return;
            const r = el.getBoundingClientRect();
            const old = next[id];
            if (!old || Math.abs(old.w - r.width) > 0.5 || Math.abs(old.h - r.height) > 0.5) {
                next[id] = { w: r.width, h: r.height };
                changed = true;
            }
        });
        if (changed) setMeasuredNodes(next);
    }, [band, measuredNodes]);

    const visRect: Rect = { x: cam.x - evp.w / 2 / cam.z, y: cam.y - evp.h / 2 / cam.z, w: evp.w / cam.z, h: evp.h / cam.z };
    const groupLayout = diagramLayout(CONTAINER_NODES, visRect, cam.z, measuredNodes);
    const sysLayout = diagramLayout(SYS_NODES, visRect, cam.z, measuredNodes);
    const grpRels = routeRels(C4_RELS, groupLayout, cam.z, visRect);
    const sysRels = routeRels(SYS_RELS, sysLayout, cam.z, visRect);

    const cards: Record<string, React.ReactNode> = {
        progress: <CardShell id="progress" tag="Center" title="Review progress" centerCard onFocus={focusCard}>
            <p>{reviewedCount} of {SECTIONS.length} sections reviewed{flagCount > 0 ? ` - ${flagCount} flagged for another look` : ''}.</p>
            <p className="rc-muted">Your marks are saved on this device only. Clearing all marks starts the pass over.</p>
        </CardShell>,
        howto: <CardShell id="howto" tag="Start here" title="How to review a section" onFocus={focusCard}>
            <ul>
                <li>Three purposes here (the Diataxis map): the diagram views are for understanding the architecture; each section card is for looking up exact files at the pinned commit; this card is for getting the review task done.</li>
                <li>Pick a card. Each is one piece of the repo, small enough for one sitting.</li>
                <li>Tap any file name - it opens that exact file at commit {COMMIT_SHORT}. Long lists unfold with "Show all files".</li>
                <li>Read, then come back and mark it: Reviewed, or Needs another look.</li>
                <li>"Not checked yet" lists what nobody has verified, so gaps stay visible.</li>
            </ul>
        </CardShell>,
        permanence: <CardShell id="permanence" tag="Permanence, honestly" title="What persists here" onFocus={focusCard}>
            <ul>
                <li>Your view and review marks are remembered in this browser's local storage on this static page. Cards stay fixed in place.</li>
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
            // after the re-render so it still fits in the detail viewport
            if (next && focusedId === id) requestAnimationFrame(() => requestAnimationFrame(() => focusCard(id)));
        }} onMark={markSection} onFocus={focusCard} />;
    }

    return <>
        <div
            ref={vpRef}
            className={`rc-viewport${dragging ? ' rc-dragging' : ''}${band === 'sys' ? ' rc-sys' : band === 'grp' ? ' rc-grp' : band === 'lo' ? ' rc-lo' : ''}`}
            onPointerDown={onPointerDown}
            onPointerMove={onPointerMove}
            onPointerUp={endPointer}
            onPointerCancel={endPointer}
            onClickCapture={onClickCapture}
            onWheel={onWheel}
        >
            <div
                className="rc-world"
                style={{
                    width: WORLD.w, height: WORLD.h,
                    transform: `translate(${evp.w / 2 - cam.x * cam.z}px, ${evp.h / 2 - cam.y * cam.z}px) scale(${cam.z})`,
                    ['--camz' as any]: cam.z,
                }}
            >
                {/* container level: C4 relationships with labels; solid =
                    observed flow or evidenced manual step, dashed = designed
                    but not active / no deployed runtime link. rc-grp only */}
                <svg className="rc-gedges" width={WORLD.w} height={WORLD.h}>
                    <defs>
                        <marker id="rc-arr" markerWidth="7" markerHeight="7" refX="6" refY="3.5" orient="auto">
                            <path d="M0,0 L7,3.5 L0,7 z" fill="var(--ds-teal)" />
                        </marker>
                        <marker id="rc-arr-dash" markerWidth="7" markerHeight="7" refX="6" refY="3.5" orient="auto">
                            <path d="M0,0 L7,3.5 L0,7 z" fill="var(--ds-ink-2)" />
                        </marker>
                    </defs>
                    {grpRels.map((g) => (
                        <polyline key={g.key} points={g.pts.map((p) => `${p.x},${p.y}`).join(' ')} fill="none"
                            className={g.dashed ? 'rc-rel-dash' : undefined}
                            markerEnd={`url(#${g.dashed ? 'rc-arr-dash' : 'rc-arr'})`} />
                    ))}
                </svg>
                <div className="rc-groups">
                    {groupLayout.map((n) => {
                        const g = n.g;
                        if (g.kind === 'external' || g.kind === 'person') {
                            return (
                                <div key={g.id} data-nid={g.id} className="rc-grpnode rc-node-ext" style={{ left: n.x, top: n.y }}>
                                    <span className="rc-grplabel">{g.label}</span>
                                    <span className="rc-grpcount">{g.sub}</span>
                                </div>
                            );
                        }
                        const members = [...(g.members ?? []), ...(g.attached ?? [])];
                        const rev = members.filter((m) => reviews[m] === 'reviewed').length;
                        return (
                            <button key={g.id} type="button" className={`rc-grpnode${g.kind === 'support' ? ' rc-node-support' : ''}`} style={{ left: n.x, top: n.y }} onClick={() => focusGroup(g.id)}>
                                <span className="rc-grplabel">{g.label}</span>
                                <span className="rc-grpcount">{g.sub}</span>
                                <span className="rc-grpcount">{members.length} {members.length === 1 ? 'section' : 'sections'}{rev > 0 ? ` · ${rev} reviewed` : ''}</span>
                            </button>
                        );
                    })}
                </div>
                {/* relationship labels sit above the nodes so long labels stay
                    readable where lines pass under neighbouring nodes */}
                <svg className="rc-glabels" width={WORLD.w} height={WORLD.h}>
                    {grpRels.map((g, i) => (
                        <g key={g.key} className={`rc-rel-badge${g.dashed ? ' rc-rel-badge-dash' : ''}`}>
                            <circle cx={g.bx} cy={g.by} r={g.r} />
                            <text x={g.bx} y={g.by}>{i + 1}</text>
                        </g>
                    ))}
                </svg>
                {/* system context level: people + the intodo system + external
                    systems, furthest zoomed out. Tapping the system drops
                    into the container level. */}
                <svg className="rc-sysedges" width={WORLD.w} height={WORLD.h}>
                    {/* markers must live in THIS svg: url(#...) into the
                        display:none container-band svg does not paint */}
                    <defs>
                        <marker id="rc-arr-sys" markerWidth="7" markerHeight="7" refX="6" refY="3.5" orient="auto">
                            <path d="M0,0 L7,3.5 L0,7 z" fill="var(--ds-teal)" />
                        </marker>
                        <marker id="rc-arr-dash-sys" markerWidth="7" markerHeight="7" refX="6" refY="3.5" orient="auto">
                            <path d="M0,0 L7,3.5 L0,7 z" fill="var(--ds-ink-2)" />
                        </marker>
                    </defs>
                    {sysRels.map((g) => (
                        <polyline key={g.key} points={g.pts.map((p) => `${p.x},${p.y}`).join(' ')} fill="none"
                            className={g.dashed ? 'rc-rel-dash' : undefined}
                            markerEnd={`url(#${g.dashed ? 'rc-arr-dash-sys' : 'rc-arr-sys'})`} />
                    ))}
                </svg>
                <div className="rc-sysnodes">
                    {sysLayout.map((n) => {
                        const g = n.g;
                        const cls = `rc-grpnode rc-node-${g.kind}`;
                        const inner = <>
                            <span className="rc-grplabel">{g.label}</span>
                            <span className="rc-grpcount">{g.sub}</span>
                        </>;
                        return g.kind === 'system' ? (
                            <button key={g.id} data-nid={g.id} type="button" className={cls} style={{ left: n.x, top: n.y }} onClick={() => {
                                viewTouched.current = true;
                                const c = clampCam({ x: 1080, y: 1280, z: 0.2 });
                                setCam(c); persist(c, pos, reviews);
                            }}>{inner}</button>
                        ) : (
                            <div key={g.id} data-nid={g.id} className={cls} style={{ left: n.x, top: n.y }}>{inner}</div>
                        );
                    })}
                </div>
                <svg className="rc-syslabels" width={WORLD.w} height={WORLD.h}>
                    {sysRels.map((g, i) => (
                        <g key={g.key} className="rc-rel-badge">
                            <circle cx={g.bx} cy={g.by} r={g.r} />
                            <text x={g.bx} y={g.by}>{i + 1}</text>
                        </g>
                    ))}
                </svg>
                {/* section level: every card belongs to a C4 container. The
                    free-form pos grid mixes containers within rows, so in the
                    section band cards ignore pos and flow under their container
                    label in reading order (LO_LAYOUT); the detail band keeps
                    the saved/default positions. */}
                {band === 'lo' && C4_CONTAINERS.map((g) => (
                    <div key={g.id} className="rc-seclabel" style={{ left: LO_LABELS[g.id].x, top: LO_LABELS[g.id].y }}>{g.label}</div>
                ))}
                {Object.keys(cards).map((id) => {
                    const p = band === 'lo' ? LO_LAYOUT[id] : pos[id];
                    return (
                        <div key={id} style={{ position: 'absolute', left: p.x, top: p.y, width: CARD_SIZES[id].w, zIndex: expandedId === id ? 5 : undefined }}>
                            {cards[id]}
                        </div>
                    );
                })}
            </div>
            {/* navigation chrome (zoom buttons, Fit, minimap) intentionally
                removed per owner request - pan and pinch remain; controls come
                back in a later iteration. */}
            {/* storage honesty without permanent chrome: the notice only
                appears when saving actually fails */}
            {savedMeta.ready && /^Not saved/.test(savedMeta.message) ? (
                <div className="rc-status rc-warn" aria-live="polite">{savedMeta.message}</div>
            ) : null}
            {/* C4 relationship key, container/system bands only: badges on
                the edges are numbered into this list; dashed = designed but
                not active (draft workflows) or no deployed runtime link */}
            <button type="button" className="rc-legend-chip rc-legend-chip-grp" onClick={() => setLegendOpen((v) => !v)}>
                {legendOpen ? 'hide key' : 'relationship key'}
            </button>
            {legendOpen ? (
                <div className="rc-legend rc-legend-grp">
                    {C4_RELS.map((r, i) => (
                        <span className="rc-legend-item" key={r.label}><span className={`rc-legend-num${r.dashed ? ' rc-legend-num-dash' : ''}`}>{i + 1}</span>{r.label}</span>
                    ))}
                    <span className="rc-legend-item rc-legend-key"><span className="rc-legend-line"></span>observed flow or manual step (repo-evidenced) · <span className="rc-legend-line rc-legend-dash"></span>designed, not active / no runtime link</span>
                </div>
            ) : null}
            <button type="button" className="rc-legend-chip rc-legend-chip-sys" onClick={() => setLegendOpen((v) => !v)}>
                {legendOpen ? 'hide key' : 'relationship key'}
            </button>
            {legendOpen ? (
                <div className="rc-legend rc-legend-sys">
                    {SYS_RELS.map((r, i) => (
                        <span className="rc-legend-item" key={r.label}><span className={`rc-legend-num${r.dashed ? ' rc-legend-num-dash' : ''}`}>{i + 1}</span>{r.label}</span>
                    ))}
                    <span className="rc-legend-item rc-legend-key"><span className="rc-legend-line"></span>observed flow or manual step (repo-evidenced) · <span className="rc-legend-line rc-legend-dash"></span>designed, not active (draft workflows)</span>
                </div>
            ) : null}
        </div>
        {overlayId && cards[overlayId] ? (
            <div className="rc-overlay" role="dialog" aria-modal="true" aria-label={TITLES[overlayId]}>
                <div className="rc-overlay-head">
                    <button type="button" className="rc-overlay-back" onClick={() => setFocusedId(null)}>Back to canvas</button>
                </div>
                <div className="rc-overlay-body">{cards[overlayId]}</div>
            </div>
        ) : null}
        {sheetOpen ? (
            <div className="rc-overlay rc-gsheet" role="dialog" aria-modal="true" aria-label={C4_CONTAINERS.find((x) => x.id === sheetGroup)!.label}>
                <div className="rc-overlay-head">
                    <button type="button" className="rc-overlay-back" onClick={() => setSheetGroup(null)}>Back to canvas</button>
                </div>
                <div className="rc-overlay-body">
                    {(() => {
                        const g = C4_CONTAINERS.find((x) => x.id === sheetGroup)!;
                        const all = [...(g.members ?? []), ...(g.attached ?? [])];
                        const rev = all.filter((m) => reviews[m] === 'reviewed').length;
                        return (
                            <>
                                <h2 className="rc-gsheet-title">{g.label}</h2>
                                <p className="rc-gsheet-sub">{all.length} {all.length === 1 ? 'section' : 'sections'}{rev > 0 ? ` · ${rev} reviewed` : ''}</p>
                                {all.map((m) => {
                                    const sec = SECTIONS.find((s) => s.id === m);
                                    const mk = reviews[m];
                                    return (
                                        <button key={m} type="button" className="rc-grow" onClick={() => { setSheetGroup(null); setFocusedId(m); }}>
                                            <span className="rc-icon" aria-hidden="true">{ICONS[m]}</span>
                                            <span className="rc-growtext">
                                                <span className="rc-growtitle">{TITLES[m]}</span>
                                                {sec ? <span className="rc-growtag">{sec.tag}</span> : null}
                                            </span>
                                            {mk === 'reviewed' && <span className="rc-badge rc-badge-done">Reviewed</span>}
                                            {mk === 'flag' && <span className="rc-badge rc-badge-flag">Another look</span>}
                                        </button>
                                    );
                                })}
                            </>
                        );
                    })()}
                </div>
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
