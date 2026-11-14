import React, { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { TextLink, useLocalState } from './files-shim';
import { COMMIT, COMMIT_SHORT, SECTIONS, Section } from '../src/sections';

type Pos = { x: number; y: number };
type Cam = { x: number; y: number; z: number };
type Mark = 'reviewed' | 'flag';
type Saved = { cam: Cam; pos: Record<string, Pos>; reviews?: Record<string, Mark>; findings?: Record<string, string> };

const WORLD = { w: 3200, h: 3200 };
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
    progress: { w: 360, h: 110 },
    howto: { w: 360, h: 110 },
    permanence: { w: 360, h: 110 },
    rule: { w: 360, h: 110 },
};
// One coherent spatial composition at every zoom:
// "approach this like designing a city", C4 sample proportions): cards are
// compact title rows on the map at EVERY band - bodies, file lists and the
// review controls live in the reading overlay one tap away - so frames
// reserve only their real compact stack and the system footprint shrinks
// to roughly 2x the external boxes, C4 System Context proportions on the
// one continuous map. The estimate must clear the tallest compact cardhead
// (counter-scaled titles peak at the section band's lowest zoom).
for (const s of SECTIONS) CARD_SIZES[s.id] = { w: 360, h: 110 };

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
    'dc-relay':      { x: COL_X[3], y: ROW_Y[4] },
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
    { id: 'c-app', label: 'itdapk - Android app', sub: 'Java/Kotlin + Chaquopy', kind: 'container', frac: { fx: 0.5, fy: 0.15 },
      members: ['list-ui', 'feed-sync', 'self-update', 'native-canvas', 'merge-core', 'bot-app', 'bot-runtime', 'bot-delivery', 'witness'],
      attached: ['tests-android', 'tests-bot'] },
    { id: 'c-xdc', label: 'itdxdc - webxdc app', sub: 'JavaScript in DeltaChat', kind: 'container', frac: { fx: 0.22, fy: 0.44 }, members: ['xdc-app', 'xdc-canvas'], attached: ['tests-xdc'] },
    { id: 'c-web', label: 'itdweb - review page', sub: 'static GitHub Pages site', kind: 'container', frac: { fx: 0.72, fy: 0.48 },
      members: ['this-page', 'review-src'], attached: ['howto', 'rule', 'permanence', 'progress'] },
    // The feed encryption tool is a runnable container in C4 terms (a CLI
    // application): it reads feed JSON on stdin and prints the IFW1 payload.
    // It does NOT sign and does NOT upload - publishing the feed page is a
    // manual handoff, so there is no edge from it to Instinct Files.
    { id: 'c-feedtool', label: 'Feed tool', sub: 'Python CLI, run by hand', kind: 'container', frac: { fx: 0.3, fy: 0.72 }, members: ['feed-tool'] },
    // Tests attach to the containers they verify; build/CI and repo docs stay
    // one honestly-labeled supporting group, not fake runtime architecture.
    { id: 'c-devsupport', label: 'Build, CI & repo docs', sub: 'supporting - not runtime', kind: 'support', frac: { fx: 0.32, fy: 0.85 },
      members: ['build-ci', 'assets-docs', 'dc-relay'] },
];
const C4_EXTERNALS: C4Node[] = [
    { id: 'x-files', label: 'Instinct Files', sub: 'encrypted feed page', kind: 'external', frac: { fx: 0.18, fy: 0.24 } },
    { id: 'x-github', label: 'GitHub', sub: 'releases + Pages', kind: 'external', frac: { fx: 0.8, fy: 0.24 } },
    { id: 'x-dc', label: 'DeltaChat network', sub: 'messages + webxdc', kind: 'external', frac: { fx: 0.72, fy: 0.78 } },
    // Turso was removed from this repo at 042141d; no database adapter is diagrammed.
];
// The developer appears on the container view as the person who builds and
// tests the apps and runs the feed tool - an ordinary external role only.
const C4_PEOPLE: C4Node[] = [
    { id: 'p-dev', label: 'Developer', sub: 'builds + publishes', kind: 'person', frac: { fx: 0.2, fy: 0.62 } },
];
const CONTAINER_NODES: C4Node[] = [...C4_CONTAINERS, ...C4_PEOPLE, ...C4_EXTERNALS];

// Detail-band nested layout, C4 zoom-in-context style (c4model.com): each
// container is a labeled frame and its section cards live INSIDE the frame,
// so zooming in reveals a container's contents within the parent box instead
// of swapping to an unrelated level page. Positions are deterministic, so
// cards stay fixed in place across visits; the saved free-form pos grid is
// kept for backward compatibility but the detail band no longer uses it.
// NEST_LABEL_H must clear the frame label at the detail band's lowest zoom:
// the label is inverse-scaled (constant screen size), so its world height
// grows as zoom falls; 78 world px clears it down to z ~ 0.55.
const NEST_PAD = 28, NEST_LABEL_H = 78, NEST_GAP = 22, NEST_COL_W = 392, NEST_FRAME_GAP = 60;
const NEST_COLS: Record<string, number> = { 'c-app': 2, 'c-xdc': 2, 'c-web': 2, 'c-feedtool': 1, 'c-devsupport': 2 };
// Two frame columns, height-balanced so the whole map fits a phone screen
// at a meaningfully higher zoom (better spread):
// the tall app frame pairs with the supporting frame on the left, the other
// three stack on the right (~3300 vs ~3470 world px, was ~2120 vs ~4330).
// Frames size themselves by DOM flow (real card heights, no estimates),
// stacked inside absolute-positioned columns. Positions are world-fixed:
// nothing re-lays with the camera.
const NEST_COLUMNS: string[][] = [['c-app', 'c-devsupport'], ['c-xdc', 'c-web', 'c-feedtool']];
const NEST_FRAME_W = (gid: string) => (NEST_COLS[gid] ?? 2) * NEST_COL_W - NEST_GAP + 2 * NEST_PAD;
// v4 composition (C4 sample proportions): the whole
// frame complex shifts right so externals can spread on ALL FOUR sides of
// the boundary - people on top, Developer/Instinct Files on the left flank,
// GitHub Actions/GitHub on the right flank, DeltaChat on the bottom. Short
// edges exit the nearest side instead of crowding the bottom strip.
const FRAME_DX = 712;
const NEST_COL_X = [40 + FRAME_DX, 40 + FRAME_DX + NEST_FRAME_W('c-app') + NEST_FRAME_GAP];
// Estimated frame/card bounds, used only to zoom toward a container or card
// before the frames have rendered. Rendering never uses these: frames size
// themselves by DOM flow so real card heights can never overflow a frame.
const NEST_EST: { frames: Record<string, { x: number; y: number; w: number; h: number }>; pos: Record<string, Pos> } = (() => {
    const frames: Record<string, { x: number; y: number; w: number; h: number }> = {};
    const pos: Record<string, Pos> = {};
    NEST_COLUMNS.forEach((col, ci) => {
        // y starts below the people row and the boundary-label strip: the
        // system boundary box's label sits in the top strip, clear of the
        // frames.
        let y = 560;
        for (const gid of col) {
            const g = C4_CONTAINERS.find((c) => c.id === gid)!;
            const all = [...(g.members ?? []), ...(g.attached ?? [])];
            const cols = NEST_COLS[gid] ?? 2;
            let cy = y + NEST_PAD + NEST_LABEL_H;
            for (let i = 0; i < all.length; i += cols) {
                const row = all.slice(i, i + cols);
                const rh = Math.max(...row.map((m) => (CARD_SIZES[m] ?? { w: 360, h: 480 }).h));
                row.forEach((m, j) => { pos[m] = { x: NEST_COL_X[ci] + NEST_PAD + j * NEST_COL_W, y: cy }; });
                cy += rh + NEST_GAP;
            }
            frames[gid] = { x: NEST_COL_X[ci], y, w: NEST_FRAME_W(gid), h: cy - NEST_GAP - y + NEST_PAD };
            y += frames[gid].h + NEST_FRAME_GAP;
        }
    });
    return { frames, pos };
})();
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
    { id: 'p-owner', label: 'Owner', sub: 'personal user', kind: 'person', frac: { fx: 0.22, fy: 0.2 } },
    { id: 'p-members', label: 'Group members', sub: 'DeltaChat chat', kind: 'person', frac: { fx: 0.78, fy: 0.2 } },
    { id: 'p-dev', label: 'Developer', sub: 'builds + publishes', kind: 'person', frac: { fx: 0.5, fy: 0.1 } },
    { id: 's-intodo', label: 'intodo', sub: 'reminders + lists', kind: 'system', frac: { fx: 0.5, fy: 0.44 } },
    { id: 'x-gha', label: 'GitHub Actions', sub: 'build + deploy on push', kind: 'external', frac: { fx: 0.84, fy: 0.44 } },
    { id: 'x-files', label: 'Instinct Files', sub: 'feed page', kind: 'external', frac: { fx: 0.18, fy: 0.7 } },
    { id: 'x-github', label: 'GitHub', sub: 'releases + Pages', kind: 'external', frac: { fx: 0.82, fy: 0.7 } },
    { id: 'x-dc', label: 'DeltaChat network', sub: 'messages + webxdc', kind: 'external', frac: { fx: 0.5, fy: 0.82 } },
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
    { a: 'p-dev', b: 'x-gha', label: 'triggers release + render-check (manual run)', dashed: true },
    { a: 'x-gha', b: 'x-github', label: 'signs + publishes APK (manual run)', dashed: true },
    // encrypt_feed.py only prints ciphertext; publishing the feed page is a
    // manual human step (rebuild + publish the File), not an integration.
    { a: 'p-dev', b: 'x-files', label: 'encrypts feed, publishes page by hand' },
];
// Supporting C4 views alongside the nested structure:
// two dynamic diagrams (each ONE verifiable causal scenario, numbered over
// time) and a deployment diagram (where each container actually runs). Same
// canvas, same pan/zoom, same numbered-badge + docked-key mechanics; every
// step names its repo evidence. Public repo content only.
// Scenario 1: a shared-list edit syncs to the group (webxdc ops path).
const FLOW_NODES: C4Node[] = [
    { id: 'f-xdc', label: 'itdxdc - webxdc app', sub: "a member's shared list", kind: 'container', frac: { fx: 0.14, fy: 0.28 } },
    { id: 'f-dc', label: 'DeltaChat network', sub: 'messages + webxdc', kind: 'external', frac: { fx: 0.5, fy: 0.16 } },
    { id: 'f-app', label: 'itdapk bot host', sub: 'Android app, python bot', kind: 'container', frac: { fx: 0.86, fy: 0.28 } },
    { id: 'f-xdc2', label: "Other members' itdxdc apps", sub: 'their shared lists', kind: 'container', frac: { fx: 0.5, fy: 0.74 } },
];
const FLOW_STEPS: C4Rel[] = [
    { a: 'f-xdc', b: 'f-dc', label: 'member edit sent as one webxdc status update {ops} · webxdc/js/app.js sendOps' },
    { a: 'f-dc', b: 'f-xdc2', label: 'relayed to every member; each app applies ops on receipt · app.js setUpdateListener', side: 1 },
    { a: 'f-dc', b: 'f-app', label: 'relayed to the bot host; trust gate, ops persisted, watermark advances · bot.py on_status_update/process_instance' },
    { a: 'f-xdc2', b: 'f-dc', label: 'later, separate phase: only when a trusted instance boots fresh, it sends a boot hint (unknown instances are not registered) · bot.py is_boot', side: 1 },
    { a: 'f-app', b: 'f-dc', label: 'if the gate authorizes that boot, the bot MAY replay restore batches (RESTORE_BATCH 50) · bot.py send_update', side: -1 },
];
// Scenario 2: the Android app list feed (a separate, manual publication).
const FEED_NODES: C4Node[] = [
    { id: 'g-dev', label: 'Developer', sub: 'build + publish', kind: 'person', frac: { fx: 0.12, fy: 0.5 } },
    { id: 'g-tool', label: 'feed encryption tool', sub: 'Python CLI', kind: 'container', frac: { fx: 0.4, fy: 0.22 } },
    { id: 'g-files', label: 'Instinct Files', sub: 'encrypted feed page', kind: 'external', frac: { fx: 0.68, fy: 0.22 } },
    { id: 'g-app', label: 'itdapk', sub: 'Android app', kind: 'container', frac: { fx: 0.88, fy: 0.6 } },
];
const FEED_STEPS: C4Rel[] = [
    { a: 'g-dev', b: 'g-tool', label: 'feed JSON in, encrypted payload printed · tools/encrypt_feed.py (print-only)' },
    { a: 'g-dev', b: 'g-files', label: 'payload published to the feed page by hand' },
    { a: 'g-files', b: 'g-app', label: 'itdapk GETs the feed when paired; the encrypted feed is returned and decrypted on device with the paired key · FeedSyncWorker.java + SyncEngine.java' },
];
const DEPLOY_NODES: C4Node[] = [
    { id: 'd-phone', label: 'Android phone', sub: 'itdapk · itdxdc in DeltaChat', kind: 'container', frac: { fx: 0.5, fy: 0.14 } },
    { id: 'd-devmachine', label: 'Developer machine', sub: 'Gradle build · feed CLI', kind: 'container', frac: { fx: 0.22, fy: 0.5 } },
    { id: 'd-github', label: 'GitHub', sub: 'Pages: itdweb · Releases: APK', kind: 'external', frac: { fx: 0.76, fy: 0.3 } },
    { id: 'd-files', label: 'Instinct Files', sub: 'encrypted feed page', kind: 'external', frac: { fx: 0.76, fy: 0.68 } },
    { id: 'd-dc', label: 'DeltaChat network', sub: 'messages + webxdc', kind: 'external', frac: { fx: 0.5, fy: 0.84 } },
];
const DEPLOY_RELS: C4Rel[] = [
    { a: 'd-phone', b: 'd-dc', label: 'messages + webxdc (RPC)' },                   // app/src/main/python/bot.py
    { a: 'd-phone', b: 'd-github', label: 'APK updates + review page (HTTPS)' },     // app/.../UpdateCheck.java, itdweb/
    { a: 'd-phone', b: 'd-files', label: 'fetches encrypted feed (HTTPS)' },         // app/.../SyncEngine.java
    { a: 'd-devmachine', b: 'd-github', label: 'git push + release upload' },        // gradlew, GitHub Releases
    { a: 'd-devmachine', b: 'd-files', label: 'publishes feed page (manual)' },      // tools/encrypt_feed.py output handoff
];
const VIEW_SETS = { flow: { nodes: FLOW_NODES, rels: FLOW_STEPS }, flowfeed: { nodes: FEED_NODES, rels: FEED_STEPS }, deploy: { nodes: DEPLOY_NODES, rels: DEPLOY_RELS } } as const;
// key lookup: short edge labels stay nameless on the map, so the docked key
// names each relation's endpoints explicitly (container + system ids)
const REL_NODE_LABEL = (id: string) => [...CONTAINER_NODES, ...SYS_NODES].find((n) => n.id === id)?.label ?? id;
// c4model.com-style element vocabulary: a kind tag on every node and frame
// (so color is never the only cue) plus a per-kind outline hue sampled from
// the C4 system-context reference and a stick-figure silhouette for
// people. Numeric contrast of every hue vs the page ground is in the
// patch notes; all outline hues pass 3:1, all tag inks pass 4.5:1.
const KIND_TAG: Record<string, string> = { container: '\u00abcontainer\u00bb', support: '\u00absupporting\u00bb', external: '\u00abexternal system\u00bb', person: '\u00abperson\u00bb', system: '\u00absoftware system\u00bb' };
const C4_PERSON_ICON = (
    <svg className="rc-personicon" viewBox="0 0 20 26" aria-hidden="true">
        <circle cx="10" cy="5" r="4.2" />
        <path d="M10 10.4c-4.6 0-7.4 2.8-7.4 6.6v4.4h14.8v-4.4c0-3.8-2.8-6.6-7.4-6.6z" />
    </svg>
);
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
type RelGeom = { key: string; pts: Pos[]; bx: number; by: number; r: number; label?: string; lines?: string[]; hw?: number; hh?: number; dashed?: boolean; hidden?: boolean; auth?: boolean };
// short on-map edge text: provenance after " · " and ";" detail stay in the
// docked key, and a trailing parenthetical is dropped (the dashed style +
// legend already say "manual run"); the key always lists the full relation
const shortRel = (l: string) => l.split(' · ')[0].split(';')[0].replace(/ \([^)]*\)\s*$/, '').trim();
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
function routeRels(rels: C4Rel[], nodes: GroupNode[], z: number, rect: Rect, textLabels = false, avoid: GroupNode[] = []): RelGeom[] {
    const M = 4 / z, CELL = 4 / z, RB = 11 / z;
    // text-label pill half-size (world px): labels wrap to short lines so
    // pills stay compact and near their edge; ~0.6em per char at the 10px
    // screen-constant font plus padding; matches the CSS pill
    const dims = (l: string) => {
        const s = shortRel(l);
        const words = s.split(' ');
        const lines: string[] = [];
        let cur = '';
        for (const w of words) {
            if (cur && cur.length + 1 + w.length > 16) { lines.push(cur); cur = w; } else cur = cur ? cur + ' ' + w : w;
        }
        if (cur) lines.push(cur);
        const maxLen = Math.max(...lines.map((x) => x.length), 1);
        return { s, lines, hw: (maxLen * 6.0 + 12) / 2 / z, hh: (lines.length * 11 + 6) / 2 / z };
    };
    // Stable map: node positions are world-fixed. Routing NEVER moves a node
    // (the old badge-room push re-laid the map per zoom and could clamp
    // several nodes into one grid cell); badges place along the route below.
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
                if (pts.length >= 2) {
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
                    // re-anchor endpoints toward the FINAL neighbours: the
                    // first anchors aimed at grid waypoints the pull may have
                    // removed, leaving segments that cut through the endpoint
                    // boxes themselves
                    if (pulled.length >= 2) {
                        pulled[0] = border(na, pulled[1].x - na.x, pulled[1].y - na.y);
                        pulled[pulled.length - 1] = border(nb, pulled[pulled.length - 2].x - nb.x, pulled[pulled.length - 2].y - nb.y);
                    }
                    pts = pulled;
                    routed = true;
                }
            }
        }
        // Opposite-direction edges on the same node pair (marked side: 1/-1)
        // get real parallel lanes, not just offset badges: shift the whole
        // route perpendicular to its direction so the two directions never
        // share one line. Endpoints move along the box border, still attached.
        if (rel.side) {
            const dx0 = nb.x - na.x, dy0 = nb.y - na.y;
            const L0 = Math.hypot(dx0, dy0) || 1;
            const off = rel.side * 30 / z;
            pts = pts.map((p) => ({ x: p.x - (dy0 / L0) * off, y: p.y + (dx0 / L0) * off }));
        }
        return { rel, na, nb, pts };
    });
    const segDist = (x: number, y: number, p: Pos, q: Pos): number => {
        const vx = q.x - p.x, vy = q.y - p.y;
        const L2 = vx * vx + vy * vy || 1;
        const t = Math.max(0, Math.min(1, ((x - p.x) * vx + (y - p.y) * vy) / L2));
        return Math.hypot(x - (p.x + vx * t), y - (p.y + vy * t));
    };
    // pass 2: labels - candidates along the route by arc length, avoiding
    // boxes, other labels, and every OTHER edge's path
    const placed: { x: number; y: number; hw: number; hh: number }[] = [];
    const geoms = routed.map(({ rel, na, nb, pts }, i) => {
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
        const d = textLabels ? dims(rel.label) : null;
        const hw = d ? d.hw : RB, hh = d ? d.hh : RB;
        const rr = Math.max(hw, hh);
        // pill-aware clearance: the rect's half-extent along the direction
        // toward the thing being avoided, not the worst-case circle
        const hitsNode = (x: number, y: number) =>
            [...nodes, ...avoid].some((n) => {
                // measured frame-label rects (pseudo-nodes): never cover them
                if (n.g.id.startsWith('lab-'))
                    return Math.abs(x - n.x) < n.hw + hw + 2 / z && Math.abs(y - n.y) < n.hh + hh + 2 / z;
                // people/external boxes render TALLER than their layout slot
                // (icon + wrapped label + kind chip) - avoid the rendered box
                if (n.g.kind === 'person' || n.g.kind === 'external') {
                    const nhh = n.g.kind === 'person' ? Math.max(n.hh, 170) : Math.max(n.hh, 130);
                    return Math.abs(x - n.x) < n.hw + hw + 8 / z && Math.abs(y - n.y) < nhh + hh + 2 / z;
                }
                // container frames are mostly empty territory at this band:
                // a pill may sit over the open interior but never over the
                // frame's label strip (approx; the measured rect is exact)
                if (n.g.kind === 'container' || n.g.kind === 'support') {
                    const sx1 = Math.min(n.x - n.hw + 818, n.x + n.hw);
                    return x + hw > n.x - n.hw && x - hw < sx1 && y - hh < n.y - n.hh + 216 && y + hh > n.y - n.hh;
                }
                return false;
            });
        const segRectDist = (p: Pos, q: Pos, x: number, y: number) => {
            let m = Infinity;
            for (let s = 0; s <= 10; s++) {
                const px = p.x + (q.x - p.x) * s / 10, py = p.y + (q.y - p.y) * s / 10;
                const dx = Math.max(Math.abs(px - x) - hw, 0), dy = Math.max(Math.abs(py - y) - hh, 0);
                m = Math.min(m, Math.hypot(dx, dy));
            }
            return m;
        };
        const inBounds = (x: number, y: number) =>
            !(x - hw < rect.x + 6 / z || x + hw > rect.x + rect.w - 6 / z || y - hh < rect.y + 6 / z || y + hh > rect.y + rect.h - 6 / z);
        const badgeHits = (x: number, y: number) =>
            hitsNode(x, y) ||
            placed.some((b) => Math.abs(x - b.x) < hw + b.hw + 4 / z && Math.abs(y - b.y) < hh + b.hh + 4 / z) ||
            routed.some((r2, j) => j !== i && r2.pts.some((p2, si) => si < r2.pts.length - 1 && segRectDist(p2, r2.pts[si + 1], x, y) < 2 / z)) ||
            !inBounds(x, y);
        const mid0 = arcAt(0.5);
        let bx = mid0.x, by = mid0.y;
        const cands: [number, number][] = [];
        for (const t of [0.5, 0.4, 0.6, 0.3, 0.7, 0.22, 0.78, 0.15, 0.85, 0.08, 0.92]) {
            const a = arcAt(t);
            const off = Math.abs(a.uy) * hw + Math.abs(a.ux) * hh + 4 / z;
            cands.push([a.x - a.uy * side * off, a.y + a.ux * side * off]);
            cands.push([a.x + a.uy * side * off, a.y - a.ux * side * off]);
        }
        cands.push([mid0.x, mid0.y]);
        let free = false;
        for (const [cx, cy] of cands) if (!badgeHits(cx, cy)) { bx = cx; by = cy; free = true; break; }
        let crowded = false;
        if (!free) {
            // crowded corner: take the candidate with the most clearance
            let bestScore = -Infinity;
            for (const [cx, cy] of cands) {
                let score = Infinity;
                for (const n of [...nodes, ...avoid]) {
                    if (n.g.kind === 'container' || n.g.kind === 'support') continue; // open interior is fair pill territory; labels avoided via lab- rects
                    if (n.g.kind === 'system' && !n.g.id.startsWith('lab-')) continue; // boundary interior likewise
                    const nhh = n.g.kind === 'person' ? Math.max(n.hh, 170) : n.g.kind === 'external' ? Math.max(n.hh, 130) : n.hh;
                    // clearance between the pill BOX and the avoided box
                    const gx = Math.max(Math.abs(cx - n.x) - n.hw - hw, 0), gy = Math.max(Math.abs(cy - n.y) - nhh - hh, 0);
                    score = Math.min(score, Math.hypot(gx, gy));
                }
                for (const b of placed) score = Math.min(score, Math.hypot(cx - b.x, cy - b.y));
                if (score > bestScore) { bestScore = score; bx = cx; by = cy; }
            }
            // Compact map: in narrow notches between neighbour boxes
            // even the best candidate touches a frame header. Hide the label
            // then, like the declutter pass below does for label piles:
            // the edge line stays and the relation stays in the docked key.
            if (bestScore < 2 / z) crowded = true;
        }
        // a pill must never stick out of the territory rect
        if (!inBounds(bx, by)) crowded = true;
        placed.push({ x: bx, y: by, hw, hh });
        const tuck = (n: GroupNode, at: number, from: number) => {
            const ux = n.x - pts[from].x, uy = n.y - pts[from].y;
            const L = Math.hypot(ux, uy) || 1;
            pts[at] = { x: pts[at].x + (ux / L) * 2.5 / z, y: pts[at].y + (uy / L) * 2.5 / z };
        };
        tuck(na, 0, 1);
        tuck(nb, pts.length - 1, pts.length - 2);
        return { key: `${rel.a}-${rel.b}`, pts, bx, by, r: rr, label: d ? d.s : undefined, lines: d ? d.lines : undefined, hw, hh, dashed: rel.dashed, hidden: crowded };
    });
    // Maps-style decluttering: a label that would
    // overlap an already-kept label on screen is hidden rather than piled
    // up - the edge line stays, and the relation remains in the docked key.
    // Greedy in relationship order: deterministic at every zoom.
    const kept: RelGeom[] = [];
    for (const g of geoms) {
        const gw = g.hw ?? g.r, gh = g.hh ?? g.r;
        if (kept.every((k) => Math.abs(g.bx - k.bx) >= gw + (k.hw ?? k.r) + 8 / z || Math.abs(g.by - k.by) >= gh + (k.hh ?? k.r) + 8 / z)) kept.push(g);
        else g.hidden = true;
    }
    return geoms;
}
// segment-segment crossing (proper interior crossings only; grazing or
// shared-port touches are connections, not crossings)
function segX(p1: Pos, p2: Pos, p3: Pos, p4: Pos): Pos | null {
    const d = (p2.x - p1.x) * (p4.y - p3.y) - (p2.y - p1.y) * (p4.x - p3.x);
    const l1 = Math.hypot(p2.x - p1.x, p2.y - p1.y), l2 = Math.hypot(p4.x - p3.x, p4.y - p3.y);
    if (Math.abs(d) < 0.2 * l1 * l2) return null;
    const t = ((p3.x - p1.x) * (p4.y - p3.y) - (p3.y - p1.y) * (p4.x - p3.x)) / d;
    const u = ((p3.x - p1.x) * (p2.y - p1.y) - (p3.y - p1.y) * (p2.x - p1.x)) / d;
    if (t <= 0.001 || t >= 0.999 || u <= 0.001 || u >= 0.999) return null;
    const x = p1.x + t * (p2.x - p1.x), y = p1.y + t * (p2.y - p1.y);
    const near = (a: Pos, b: Pos) => Math.hypot(a.x - b.x, a.y - b.y) < 6;
    if (near({ x, y }, p1) || near({ x, y }, p2) || near({ x, y }, p3) || near({ x, y }, p4)) return null;
    return { x, y };
}
// Smooth an authored route for rendering: rounded corners plus flyover hops
// where this (later-drawn) edge crosses another. The route skeleton (pts)
// never changes - corners round toward the corner point and hops arc over
// the crossing, so territory geometry and pill anchors stay exact. Radii
// counter-scale with zoom so joins read at phone size and shrink away at Fit.
function edgePathD(pts: Pos[], hops: Pos[], z: number): string {
    const P: Pos[] = [];
    for (const p of pts) {
        const q = P[P.length - 1];
        if (q && Math.hypot(p.x - q.x, p.y - q.y) < 0.01) continue;
        P.push({ x: p.x, y: p.y });
    }
    for (let i = P.length - 2; i > 0; i--) {
        const a = P[i - 1], b = P[i], c = P[i + 1];
        const ux = b.x - a.x, uy = b.y - a.y, vx = c.x - b.x, vy = c.y - b.y;
        if (Math.abs(ux * vy - uy * vx) <= 0.001 * (Math.hypot(ux, uy) * Math.hypot(vx, vy))) P.splice(i, 1);
    }
    if (P.length < 2) return '';
    const segs: { a: Pos; ux: number; uy: number; len: number }[] = [];
    for (let i = 0; i + 1 < P.length; i++) {
        const dx = P[i + 1].x - P[i].x, dy = P[i + 1].y - P[i].y;
        const len = Math.hypot(dx, dy) || 1e-6;
        segs.push({ a: P[i], ux: dx / len, uy: dy / len, len });
    }
    // hops win space over corner rounding: place them first, then corners
    // shrink to whatever room remains
    const segHops: { t: number; r: number }[][] = segs.map(() => []);
    for (const h of hops) {
        for (let si = 0; si < segs.length; si++) {
            const s = segs[si];
            const t = (h.x - s.a.x) * s.ux + (h.y - s.a.y) * s.uy;
            if (t < 0.5 || t > s.len - 0.5) continue;
            const px = s.a.x + s.ux * t, py = s.a.y + s.uy * t;
            if (Math.hypot(h.x - px, h.y - py) > 2) continue;
            const r = Math.min(6 / z, t - 0.5, s.len - t - 0.5);
            if (r < 1.6) continue; // at the port itself: reads as a connection
            segHops[si].push({ t, r });
            break;
        }
    }
    const cornerR = (ci: number): number => {
        let rc = Math.min(9 / z, segs[ci - 1].len * 0.4, segs[ci].len * 0.4);
        for (const h of segHops[ci - 1]) rc = Math.min(rc, segs[ci - 1].len - h.t - h.r - 0.5);
        for (const h of segHops[ci]) rc = Math.min(rc, h.t - h.r - 0.5);
        return Math.max(0, rc);
    };
    for (const hs of segHops) {
        hs.sort((a, b) => a.t - b.t);
        for (let k = 0; k + 1 < hs.length; k++) {
            const gap = hs[k + 1].t - hs[k].t;
            const want = hs[k].r + hs[k + 1].r + 0.5;
            if (gap < want) {
                const r = (gap - 0.5) / 2;
                if (r < 1.6) { hs[k].r = 0; hs[k + 1].r = 0; }
                else { hs[k].r = Math.min(hs[k].r, r); hs[k + 1].r = Math.min(hs[k + 1].r, r); }
            }
        }
    }
    let d = `M${P[0].x} ${P[0].y}`;
    for (let i = 0; i < segs.length; i++) {
        const s = segs[i];
        const isLast = i === segs.length - 1;
        const rc = isLast ? 0 : cornerR(i + 1);
        const hs = segHops[i].filter((h) => h.r >= 1.6);
        let curT = i === 0 ? 0 : cornerR(i);
        const lineTo = (t: number) => { d += ` L${(s.a.x + s.ux * t).toFixed(2)} ${(s.a.y + s.uy * t).toFixed(2)}`; };
        for (const h of hs) {
            if (h.t - h.r > curT) lineTo(h.t - h.r);
            d += ` A${h.r.toFixed(2)} ${h.r.toFixed(2)} 0 0 1 ${(s.a.x + s.ux * (h.t + h.r)).toFixed(2)} ${(s.a.y + s.uy * (h.t + h.r)).toFixed(2)}`;
            curT = Math.max(curT, h.t + h.r);
        }
        if (isLast) lineTo(s.len);
        else {
            const W = P[i + 1], ns = segs[i + 1];
            lineTo(s.len - rc);
            d += ` Q${W.x} ${W.y} ${(W.x + ns.ux * rc).toFixed(2)} ${(W.y + ns.uy * rc).toFixed(2)}`;
        }
    }
    return d;
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

function CardShell({ id, tag, purpose, title, mark, centerCard, onFocus, children }: {
    id: string; tag: string; purpose?: string; title: string; mark?: Mark; centerCard?: boolean; onFocus: (id: string) => void; children: React.ReactNode;
}) {
    // Nothing inside the map is clickable - the card
    // head shows title/marks only; reading + review controls live in the
    // reading sheet, opened from the dock menu outside the territory.
    return <div className={`rc-card${centerCard ? ' rc-center' : ''}`} data-card={id}>
        <div className="rc-cardhead">
            <div className="rc-headtext">
                <span className="rc-icon" aria-hidden="true">{ICONS[id]}</span>
                <div>
                    <div className="rc-tag">{tag}</div>
                    {purpose && <div className="rc-purpose">{purpose}</div>}
                    <h3>{title}</h3>
                </div>
            </div>
        </div>
        {mark === 'reviewed' && <span className="rc-badge rc-badge-done">Reviewed</span>}
        {mark === 'flag' && <span className="rc-badge rc-badge-flag">Another look</span>}
        {children}
    </div>;
}

function SectionCard({ s, mark, expanded, finding, onToggleExpand, onMark, onFinding, onFocus }: {
    s: Section; mark?: Mark; expanded: boolean; finding?: string; onToggleExpand: (id: string) => void; onMark: (id: string, m?: Mark) => void; onFinding: (id: string, text: string) => void; onFocus: (id: string) => void;
}) {
    const shown = expanded ? s.files : s.files.slice(0, 5);
    const cycle = () => {
        if (!mark) onMark(s.id, 'reviewed');
        else if (mark === 'reviewed') onMark(s.id, 'flag');
        else onMark(s.id, undefined);
    };
    const label = !mark ? 'Mark reviewed' : mark === 'reviewed' ? 'Needs another look' : 'Clear mark';
    return <CardShell id={s.id} tag={s.tag} purpose="Look up - reference" title={s.title} mark={mark} onFocus={onFocus}>
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
        <p className="rc-nchead">Your findings</p>
        <textarea
            className="rc-findings"
            rows={3}
            value={finding ?? ''}
            placeholder="What you found reviewing this section - saved in this browser only; not synced or sent anywhere."
            onChange={(e) => onFinding(s.id, e.target.value)}
        />
        <div className="rc-reviewbtns">
            <button type="button" onClick={cycle}>{label}</button>
        </div>
    </CardShell>;
}

function Canvas() {
    const [saved, setSaved, savedMeta] = useLocalState<Saved | null>('review-canvas.v1', null);
    const [cam, setCam] = useState<Cam>(saved?.cam ?? { x: 1600, y: 1600, z: 0.17 });
    const [pos, setPos] = useState<Record<string, Pos>>(sanePositions(saved?.pos ?? {}));
    const [focusedId, setFocusedId] = useState<string | null>(null);
    const [sheetGroup, setSheetGroup] = useState<string | null>(null);
    const [dock, setDock] = useState<'none' | 'menu' | 'legend'>('none');
    const [view, setView] = useState<'structure' | 'flow' | 'flowfeed' | 'deploy'>('structure');
    const [measuredNodes, setMeasuredNodes] = useState<Record<string, { w: number; h: number }>>({});
    // Rendered frame-label world rects (measured from the DOM): label pills
    // must never cover a frame label - the est frame box does not match the
    // rendered frame, so avoidance uses the real label rect.
    const [labelRects, setLabelRects] = useState<Record<string, Rect>>({});
    // Frame-label fit: at the card-visible bands the absolute header must
    // never overlap the first card. Measured per frame against the actual
    // card strip; the label font steps down (and row2 drops at high steps)
    // until it clears. Deterministic per (frame, zoom), world-fixed
    // geometry untouched - only the header's own size yields.
    const [labelFit, setLabelFit] = useState<Record<string, number>>({});
    const frameLabelRefs = useRef<Record<string, HTMLDivElement | null>>({});
    const [reviews, setReviews] = useState<Record<string, Mark>>(saved?.reviews ?? {});
    const [findings, setFindings] = useState<Record<string, string>>(saved?.findings ?? {});
    const [expandedId, setExpandedId] = useState<string | null>(null);
    const [vp, setVp] = useState({ w: 0, h: 0 });
    const fitted = useRef(false);
    const appliedSaved = useRef(false);
    const viewTouched = useRef(false);
    const [dragging, setDragging] = useState(false);
    const [panning, setPanning] = useState(false);
    const vpRef = useRef<HTMLDivElement>(null);
    const pointers = useRef(new Map<number, Pos>());
    const g = useRef<{
        mode: 'pan' | 'card' | 'pinch' | null; cardId?: string;
        start: Pos; last: Pos; off: Pos; moved: boolean;
        downTime?: number; textDown?: boolean; selDown?: boolean; pt?: string;
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
            setFindings(saved.findings ?? {});
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

    const persist = (c: Cam, p: Record<string, Pos>, r: Record<string, Mark>, f: Record<string, string> = findings) => { if (savedMeta.ready) setSaved({ cam: c, pos: p, reviews: r, findings: f }); };

    const recordFinding = (id: string, text: string) => {
        const f = { ...findings };
        if (text) f[id] = text; else delete f[id];
        setFindings(f);
        persist(cam, pos, reviews, f);
    };

    const evp = vp.w > 0 ? vp : { w: 390, h: 560 };
    const toWorld = (p: Pos, c: Cam): Pos => ({ x: c.x + (p.x - evp.w / 2) / c.z, y: c.y + (p.y - evp.h / 2) / c.z });

    function fitView() {
        const el = vpRef.current;
        const w = el ? el.clientWidth : vp.w, h = el ? el.clientHeight : vp.h;
        if (!w || !h) return;
        // Fit the actual RENDERED content - frames, external/person nodes,
        // edge routes AND numbered badges. Structure view: measured live from
        // the DOM (zoom-stable layout, so one measure is exact). Flow/feed/
        // deploy views: nodes keep constant SCREEN size, so their world
        // bounds move with zoom and a DOM measure at the old zoom is stale at
        // the fitted one - iterate the same layout/route functions at the
        // candidate zoom until it converges, then fit that union.
        // Fit must place the COMPLETE union (nodes incl. labels, routes,
        // badges) inside the unobscured canvas area ABOVE the dock - content
        // hidden behind the dock bar is not fitted (live writer finding).
        // Measure the dock; fall back to a conservative reserve.
        const dockEl = el ? el.querySelector('.rc-dockbar') as HTMLElement | null : null;
        const dockH = dockEl && dockEl.offsetParent ? dockEl.getBoundingClientRect().height + 14 : 64;
        const fw = w, fh = Math.max(120, h - dockH);
        let c: Cam = { x: 1120, y: 1500, z: clampZ(Math.min(fw / 2380, fh / 2900) * 0.98) };
        const fitZ = (uw: number, uh: number) => clampZ(Math.min(fw / (uw + 80), fh / (uh + 80)) * 0.98);
        if (view !== 'structure') {
            const set = VIEW_SETS[view as 'flow' | 'flowfeed' | 'deploy'];
            let z = cam.z, x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
            for (let it = 0; it < 10; it++) {
                const ns = diagramLayout(set.nodes as C4Node[], layRect, z, measuredNodes);
                const rs = routeRels(set.rels as C4Rel[], ns, z, layRect, true);
                x0 = Infinity; y0 = Infinity; x1 = -Infinity; y1 = -Infinity;
                ns.forEach((n) => { x0 = Math.min(x0, n.x - n.hw); y0 = Math.min(y0, n.y - n.hh); x1 = Math.max(x1, n.x + n.hw); y1 = Math.max(y1, n.y + n.hh); });
                rs.forEach((r) => {
                    if (r.hidden) return; // decluttered badges are not rendered - they must not inflate the fit
                    r.pts.forEach((pt) => { x0 = Math.min(x0, pt.x); y0 = Math.min(y0, pt.y); x1 = Math.max(x1, pt.x); y1 = Math.max(y1, pt.y); });
                    x0 = Math.min(x0, r.bx - r.r); y0 = Math.min(y0, r.by - r.r); x1 = Math.max(x1, r.bx + r.r); y1 = Math.max(y1, r.by + r.r);
                });
                if (!isFinite(x0) || x1 - x0 < 1 || y1 - y0 < 1) break;
                const nz = fitZ(x1 - x0, y1 - y0);
                // strictly shrink: smaller z only declutters more, so the
                // union measured at the final z is the one that must fit
                if (nz >= z || it === 9) break;
                z = nz;
            }
            // center the union in the visible rect (0..fw, 0..fh): the cam is
            // the world point at the FULL viewport center, so shift it by
            // the dock reserve in world units.
            if (isFinite(x0) && x1 > x0 && y1 > y0) c = { x: (x0 + x1) / 2, y: (y0 + y1) / 2 + (h - fh) / (2 * z), z };
        } else {
        // Band-independent fit: the map is one static world layout, but
        // external/person nodes and edge badges only RENDER at the container/
        // system bands - a DOM measure taken at detail zoom misses them and
        // they overflow after Fit (live writer finding, e331264). Compute the
        // union from the same layout constants the renderer uses; node half-
        // heights are inflated to the real band-rendered size (label wraps,
        // kind chip, person icon), which layout/routing deliberately does not
        // use. Iterate z because edge routes and badges depend on it.
        {
            const fitHH = (n: GroupNode) => (n.g.kind === 'person' ? 170 : n.g.kind === 'external' ? 130 : n.hh);
            let z = cam.z, x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
            for (let it = 0; it < 10; it++) {
                // badge-size extents, not pill extents: label pills are
                // constrained inside the territory rect by placement, so the
                // territory-spanning union already covers them at any band
                const rs = [...routeRels(C4_RELS, groupLayout, z, layRect), ...routeRels(GRP_EXTRA_RELS, sysLayout, z, layRect), ...routeRels(SYS_RELS, sysLayout, z, layRect)];
                x0 = Infinity; y0 = Infinity; x1 = -Infinity; y1 = -Infinity;
                [...groupLayout, ...sysLayout].forEach((n) => {
                    const hh = fitHH(n);
                    x0 = Math.min(x0, n.x - n.hw - 8); y0 = Math.min(y0, n.y - hh - 8);
                    x1 = Math.max(x1, n.x + n.hw + 8); y1 = Math.max(y1, n.y + hh + 8);
                });
                // the territory itself is part of the union: label pills are
                // constrained inside it, so fitting the territory fits them
                x0 = Math.min(x0, layRect.x); y0 = Math.min(y0, layRect.y);
                x1 = Math.max(x1, layRect.x + layRect.w); y1 = Math.max(y1, layRect.y + layRect.h);
                rs.forEach((r) => {
                    r.pts.forEach((pt) => { x0 = Math.min(x0, pt.x - 10); y0 = Math.min(y0, pt.y - 10); x1 = Math.max(x1, pt.x + 10); y1 = Math.max(y1, pt.y + 10); });
                    // badge circle plus stroke/halo: inflate past r.r
                    x0 = Math.min(x0, r.bx - r.r - 10); y0 = Math.min(y0, r.by - r.r - 10); x1 = Math.max(x1, r.bx + r.r + 10); y1 = Math.max(y1, r.by + r.r + 10);
                });
                if (!isFinite(x0) || x1 - x0 < 1 || y1 - y0 < 1) break;
                const nz = fitZ(x1 - x0, y1 - y0);
                // strictly shrink, same as the view branch: the union measured
                // at the final z is the one that must fit
                if (nz >= z || it === 9) break;
                z = nz;
            }
            if (isFinite(x0) && x1 > x0 && y1 > y0) c = { x: (x0 + x1) / 2, y: (y0 + y1) / 2 + (h - fh) / (2 * z), z };
        }
        }
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
        // Measure the real rendered position when the card is on screen
        // (frames lay cards out by DOM flow, so estimates are a fallback).
        const world = vpRef.current?.querySelector('.rc-world') as HTMLElement | null;
        let w = el?.offsetWidth || CARD_SIZES[id].w;
        let h = el?.offsetHeight || CARD_SIZES[id].h;
        let ctr: Pos;
        if (el && world && el.getBoundingClientRect().width > 0) {
            const wr = world.getBoundingClientRect();
            const cr = el.getBoundingClientRect();
            w = cr.width / cam.z; h = cr.height / cam.z;
            ctr = { x: (cr.left - wr.left) / cam.z + w / 2, y: (cr.top - wr.top) / cam.z + h / 2 };
        } else {
            const p = NEST_EST.pos[id] ?? pos[id] ?? DEFAULT_POS[id];
            ctr = { x: p.x + w / 2, y: p.y + h / 2 };
        }
        const z = clampZ(Math.min(evp.w / (w + 80), evp.h / (h + 48), 1.2));
        const c = clampCam({ x: ctr.x, y: ctr.y, z });
        setCam(c); persist(c, pos, reviews);
    };

    // Context level -> section level: zoom the camera to a group's bounds so
    // its member concept cards fill the view (lands inside the section band)
    const focusGroup = (gid: string) => {
        viewTouched.current = true;
        const g = C4_CONTAINERS.find((x) => x.id === gid)!;
        // Zoom into the container's nested frame on every device: the parent
        // box stays the frame of reference while its contents are revealed
        // inside it. On phones the frame dwarfs the viewport, so the
        // detail-band effect below aligns its top-left (label + first cards
        // readable); the list sheet stays available per frame via its List
        // button, as an explicit choice rather than the tap's replacement.
        pendingFrame.current = gid;
        const f = NEST_EST.frames[gid];
        const bw = f.w, bh = f.h;
        const minX = f.x, minY = f.y;
        // Fit the group's frame; never land wider than the detail band, so a
        // container tap always arrives inside the nested frame, even on a
        // phone where the frame is many viewports wide.
        const zFit = Math.min(evp.w / (bw + 140), evp.h / (bh + 140), 0.75);
        const z = clampZ(Math.max(zFit, LO_Z + 0.04));
        const c = clampCam({ x: minX + bw / 2, y: minY + bh / 2, z });
        setCam(c); persist(c, pos, reviews);
    };

    // On phones Focus opens a full-screen readable overlay (scrollable, real
    // text size) instead of zooming the canvas. Lock page scroll while open.
    // the reading sheet is the ONLY card reader now (no in-map clickables),
    // so it opens at every viewport width, not just phones
    const overlayId = focusedId;
    const sheetOpen = sheetGroup !== null && evp.w < 560;
    useEffect(() => {
        if (!overlayId && !sheetOpen) return;
        const prev = document.body.style.overflow;
        document.body.style.overflow = 'hidden';
        return () => { document.body.style.overflow = prev; };
    }, [overlayId, sheetOpen]);

    const band = cam.z < SYS_Z ? 'sys' : cam.z < GROUP_Z ? 'grp' : cam.z < LO_Z ? 'lo' : 'detail';
    // Fit each frame header into the reserved strip above its cards: at the
    // card-visible bands the absolute label must clear the first card row.
    // Step the label's font scale down until it fits (row2 drops from step
    // 3); relax one step when there is comfortable room again. Hysteresis
    // avoids oscillation; only bands with visible cards are measured.
    const labelFitBand = useRef<string>('');
    useLayoutEffect(() => {
        if (band !== 'lo' && band !== 'detail') return;
        // fresh evaluation on every band entry: start from the full header
        if (labelFitBand.current !== band) {
            labelFitBand.current = band;
            setLabelFit({});
            return;
        }
        // grow-only within a band visit (monotone -> no oscillation): the
        // header steps smaller until it clears the first card row.
        setLabelFit((cur) => {
            let changed = false;
            const next = { ...cur };
            for (const gid of Object.keys(frameLabelRefs.current)) {
                const el = frameLabelRefs.current[gid];
                const cards = el?.parentElement?.querySelector('.rc-frame-cards') as HTMLElement | null;
                if (!el || !cards || cards.offsetParent === null) continue;
                // real strip = where the first visible card actually starts
                // (the cards div spans the frame; its padding reserves the strip)
                const firstCard = ([...cards.children] as HTMLElement[]).find((c) => c.offsetParent !== null);
                const stripTop = firstCard ? firstCard.offsetTop : cards.offsetTop + (parseFloat(getComputedStyle(cards).paddingTop) || 0);
                const reserve = stripTop - el.offsetTop;
                const need = el.offsetHeight;
                const lvl = cur[gid] ?? 0;
                if (need > reserve + 1 && lvl < 8) { next[gid] = lvl + 1; changed = true; }
            }
            return changed ? next : cur;
        });
    });
    // Detail-band landing correction. The C4 bands re-lay around wherever
    // the camera is, but nested content lives at fixed world coordinates,
    // so wheel-zooming through the container band can surface empty canvas.
    // Deliberate navigation only: tapping a container centers its frame.
    // There is NO automatic landing correction - with one stable world map
    // the camera simply stays where the user leaves it at every zoom.
    const pendingFrame = useRef<string | null>(null);
    useEffect(() => {
        if (view !== 'structure' || band !== 'detail') return;
        const gid = pendingFrame.current;
        if (!gid) return;
        const vp = vpRef.current;
        const worldEl = vp?.querySelector('.rc-world') as HTMLElement | null;
        if (!vp || !worldEl) return;
        const wr = worldEl.getBoundingClientRect();
        const toWorld = (r: DOMRect) => ({
            x0: (r.left - wr.left) / cam.z, y0: (r.top - wr.top) / cam.z,
            x1: (r.right - wr.left) / cam.z, y1: (r.bottom - wr.top) / cam.z,
        });
        pendingFrame.current = null;
        const el = vp.querySelector(`[data-frame="${gid}"]`);
        if (el) {
            const b = toWorld(el.getBoundingClientRect());
            let cx = (b.x0 + b.x1) / 2, cy = (b.y0 + b.y1) / 2;
            // A frame larger than the viewport centers as a void; align
            // its top-left instead so the parent label shows with its
            // contents below/right - the nested reading.
            if ((b.y1 - b.y0) * cam.z > evp.h * 0.9) cy = b.y0 + (evp.h / 2 - 16) / cam.z;
            if ((b.x1 - b.x0) * cam.z > evp.w * 0.95) cx = b.x0 + (evp.w / 2 - 16) / cam.z;
            const c = clampCam({ x: cx, y: cy, z: cam.z });
            if (Math.abs(c.x - cam.x) > 1 || Math.abs(c.y - cam.y) > 1) { setCam(c); persist(c, pos, reviews); }
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [band, view]);

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
        // cards are fixed in place: dragging anywhere pans
        // the canvas; card positions only change through code, never by hand.
        // Node boxes (rc-grpnode) are diagram surface, not controls: at phone
        // zoom they cover most of the screen, so a drag starting on one must
        // still pan (a drag suppresses the tap-click via onClickCapture);
        // a tap with no movement still focuses. Genuine controls inside the
        // canvas (Focus buttons, the List pill, links, inputs) keep the
        // gesture to themselves.
        const btn = t.closest('button');
        if (t.closest('a') || t.closest('textarea') || t.closest('input') || (btn && !btn.classList.contains('rc-grpnode'))) { g.current.mode = null; return; }
        g.current.start = p; g.current.last = p;
        // Long-press on selectable content starts native text selection on
        // touch devices; a drag that begins after that hold belongs to the
        // selection, not to the map (checked in onPointerMove). A press that
        // lands INSIDE an existing selection is a selection adjustment, not
        // a pan.
        g.current.downTime = performance.now();
        g.current.pt = e.pointerType;
        g.current.textDown = !!t.closest('.rc-card p, .rc-card li, .rc-fbody, .rc-nlabel, .rc-sublabel');
        const sel0 = window.getSelection?.();
        g.current.selDown = !!(sel0 && sel0.rangeCount > 0 && !sel0.isCollapsed && sel0.anchorNode && t.contains(sel0.anchorNode));
        g.current.mode = 'pan';
        setDragging(true);
    };

    const onPointerMove = (e: React.PointerEvent<HTMLDivElement>) => {
        if (!pointers.current.has(e.pointerId)) return;
        const p = { x: e.clientX, y: e.clientY };
        pointers.current.set(e.pointerId, p);
        const gg = g.current;
        if (!gg.moved && gg.mode && Math.hypot(p.x - gg.start.x, p.y - gg.start.y) > 6) {
            // Disambiguate a deliberate text selection from a map pan. A
            // press inside an active selection is a selection adjustment -
            // never pan. On TOUCH, a long-press (hold >450ms without moving)
            // or a selection that already formed is the native selection
            // gesture - yield. A mouse drag always pans; native drag-select
            // is cleared at recognition and suppressed by rc-panning while
            // the gesture is active.
            const sel1 = window.getSelection?.();
            const selActive = !!(sel1 && sel1.rangeCount > 0 && !sel1.isCollapsed);
            if (gg.textDown && (gg.selDown || (gg.pt === 'touch' && (selActive || performance.now() - (gg.downTime || 0) > 450)))) {
                gg.mode = null; setDragging(false); return;
            }
            gg.moved = true;
            try { vpRef.current?.setPointerCapture(e.pointerId); } catch { /* noop */ }
            // A recognized map gesture owns the drag from here: clear any
            // selection the first few px started (live writer finding: drag
            // panned AND selected at once). Selection stays available when
            // idle - clicks, double-click word select, Focus overlay, fields.
            setPanning(true);
            try { window.getSelection()?.removeAllRanges(); } catch { /* noop */ }
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
    // instead of hidden behind buttons
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
            // Touch tap resolution. Container frames are divs (the collapsed
            // frame IS the tap target), and Chrome's touch hit-adjustment
            // retargets taps on small non-button divs to the nearest ancestor,
            // so the native click never reaches the frame (mouse clicks are
            // unaffected). Resolve a no-movement touch tap at its exact point
            // instead; genuine controls (List pill, links) never reach this
            // branch because pointerdown already nulled the gesture mode.
            if (e.pointerType === 'touch' && e.type === 'pointerup' && gg.mode === 'pan' && !gg.moved && band === 'grp') {
                const hit = document.elementFromPoint(e.clientX, e.clientY);
                const frame = hit?.closest?.('[data-frame]');
                const gid = frame?.getAttribute('data-frame');
                if (gid) { gg.mode = null; setDragging(false); setPanning(false); focusGroup(gid); return; }
            }
            if (gg.mode) { viewTouched.current = true; persist(cam, pos, reviews); }
            gg.mode = null; setDragging(false); setPanning(false);
        }
    };

    const onClickCapture = (e: React.MouseEvent) => {
        if (g.current.moved) { e.stopPropagation(); e.preventDefault(); g.current.moved = false; }
    };

    const reviewedCount = SECTIONS.filter((s) => reviews[s.id] === 'reviewed').length;
    const flagCount = SECTIONS.filter((s) => reviews[s.id] === 'flag').length;
    const findingsCount = SECTIONS.filter((s) => (findings[s.id] ?? '').trim().length > 0).length;
    useEffect(() => {
        const sel = view !== 'structure' ? '.rc-viewnodes .rc-grpnode' : band === 'grp' ? '.rc-groups .rc-grpnode' : band === 'sys' ? '.rc-sysnodes .rc-grpnode' : null;
        if (!sel) return;
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
    }, [band, view, measuredNodes]);

    useEffect(() => {
        const next: Record<string, Rect> = {};
        document.querySelectorAll('.rc-frame-label').forEach((el) => {
            const id = (el.closest('.rc-frame') as HTMLElement | null)?.dataset.frame;
            if (!id) return;
            const r = el.getBoundingClientRect();
            if (r.width < 2 || r.height < 2) return;
            next[id] = { x: cam.x - evp.w / 2 / cam.z + r.left / cam.z, y: cam.y - evp.h / 2 / cam.z + r.top / cam.z, w: r.width / cam.z, h: r.height / cam.z };
        });
        setLabelRects((old) => {
            const keys = new Set([...Object.keys(old), ...Object.keys(next)]);
            for (const k of keys) {
                const a = old[k], b = next[k];
                if (!a || !b || Math.abs(a.x - b.x) > 1 || Math.abs(a.y - b.y) > 1 || Math.abs(a.w - b.w) > 1 || Math.abs(a.h - b.h) > 1) return next;
            }
            return old;
        });
    });

    const visRect: Rect = { x: cam.x - evp.w / 2 / cam.z, y: cam.y - evp.h / 2 / cam.z, w: evp.w / cam.z, h: evp.h / cam.z };
    // Laid out against exactly the visible screen,
    // the diagrams squeezed into one screen and the long edges crisscrossed
    // the middle. Lay the C4 bands out over MORE canvas than one screen and
    // let pan/zoom do its job. The spread rect shares the visible center, so
    // the current framing is kept while nodes gain real separation.
    // The supporting views have few nodes; spreading them past the screen
    // would just clip them, so only the structure bands use the canvas.
    // Phones spread less: their first landing must still show whole nodes.
    const SPREAD_X = view !== 'structure' ? 1.1 : evp.w < 560 ? 1.15 : 1.35;
    const SPREAD_Y = view !== 'structure' ? 1.1 : evp.w < 560 ? 1.15 : 1.3;
    // One fixed world-anchored layout for every band and view:
    // a single continuous map. Bands change only how much
    // detail renders inside each frame, never where anything sits; zoom and
    // pan never re-lay.
    const layRect: Rect = { x: 40, y: 40, w: WORLD.w - 80, h: WORLD.h - 80 };
    // One stable world map: at the container and
    // container/system bands each container renders as its SAME frame with
    // its FULL detail bounds reserved at every zoom; deeper zooms reveal
    // chips, then full cards, inside those same bounds. Edge endpoints use
    // the same NEST_EST rects the frames render with, so lines and boxes
    // match at every zoom. Externals/people are fixed-position nodes below
    // the frames, at the SAME world positions in both structure layouts.
    // Nothing re-lays with the camera.
    const frameNodes: GroupNode[] = NEST_COLUMNS.flatMap((col) =>
        col.map((gid) => {
            const g = C4_CONTAINERS.find((c) => c.id === gid)!;
            const f = NEST_EST.frames[gid];
            return { g, x: f.x + f.w / 2, y: f.y + f.h / 2, hw: f.w / 2, hh: f.h / 2 };
        }));
    const extNode = (id: string, defs: C4Node[], cx: number, cy: number, hw = 160, hh = 90): GroupNode =>
        ({ g: defs.find((x) => x.id === id)!, x: cx, y: cy, hw, hh });
    // Externals flank the frame complex on all four sides (v4 city spread);
    // nodes shared by the container and system layouts keep identical world
    // coordinates.
    // Externals keep their SYSTEM-band positions AND size at the container
    // band: the same city spread around the boundary at every zoom (LOD
    // changes visibility only, never territory). People and GitHub Actions
    // stay visible at the container band too.
    const groupLayout: GroupNode[] = [
        ...frameNodes,
        extNode('p-owner', SYS_NODES, 1050, 280, 240, 150), extNode('p-members', SYS_NODES, 2150, 280, 240, 150),
        extNode('p-dev', SYS_NODES, 380, 900, 240, 150),
        extNode('x-gha', SYS_NODES, 2820, 900, 240, 150), extNode('x-files', SYS_NODES, 380, 1400, 240, 150),
        extNode('x-github', SYS_NODES, 2820, 1400, 240, 150), extNode('x-dc', SYS_NODES, 1600, 2240, 240, 150),
    ];
    // System band (c4model.com sample as reference):
    // ONE fixed system footprint at every zoom - the boundary rect below is
    // the same world rect at the system, container and section bands; only
    // what is visible inside it changes with zoom (LOD), never geometry.
    // v4 city spread: the boundary sits centered in a wider world with the
    // seven externals spread on all four sides by relationship, each edge
    // leaving the nearest side, like the C4 System Context sample.
    const sysLayout: GroupNode[] = [
        { g: SYS_NODES.find((x) => x.id === 's-intodo')!, x: 1600, y: 1220, hw: 868, hh: 720 },
        extNode('p-owner', SYS_NODES, 1050, 280, 240, 150), extNode('p-members', SYS_NODES, 2150, 280, 240, 150),
        extNode('p-dev', SYS_NODES, 380, 900, 240, 150),
        extNode('x-gha', SYS_NODES, 2820, 900, 240, 150), extNode('x-files', SYS_NODES, 380, 1400, 240, 150),
        extNode('x-github', SYS_NODES, 2820, 1400, 240, 150), extNode('x-dc', SYS_NODES, 1600, 2240, 240, 150),
    ];
    // Container band edges: the frame-level relationships plus the
    // system-level edges that do not duplicate a frame edge (people using
    // the system, and the manual publish chain), routed against the same
    // fixed boundary/externals territory.
    const GRP_EXTRA_RELS = SYS_RELS.filter((r) =>
        r.a === 'p-owner' || r.a === 'p-members' || (r.a === 'p-dev' && r.b === 'x-gha') || r.a === 'x-gha' || (r.a === 'p-dev' && r.b === 'x-files'));
    // Authored container-band composition: every relationship has an
    // explicit port-to-port route through dedicated gutters/lanes, and its
    // label is a fixed world-size pill sitting on its own connector (labels
    // instead of numbers; full wording stays in the docked key). No badge is
    // ever suppressed or re-placed by collision heuristics at this band.
    const AUTH_GRP: Record<string, { pts: [number, number][]; pill: [number, number]; lines: string[] }> = {
        'c-app-x-files': { pts: [[1240, 1200], [690, 1200], [690, 1400], [620, 1400]], pill: [1000, 1200], lines: ['feed (HTTPS)'] },
        'c-app-x-github': { pts: [[1300, 560], [1300, 510], [3120, 510], [3120, 1400], [3060, 1400]], pill: [2750, 510], lines: ['update check', '+ APK download'] },
        'c-app-x-dc': { pts: [[1130, 1350], [1574, 1350], [1600, 1350], [1600, 2090]], pill: [1250, 1350], lines: ['bot bridge', '+ webxdc (RPC)'] },
        'c-xdc-x-dc': { pts: [[2090, 890], [2452, 890], [2560, 890], [2560, 2240], [1840, 2240]], pill: [2200, 890], lines: ['webxdc updates'] },
        'c-web-x-github': { pts: [[2090, 1350], [2452, 1350], [2520, 1350], [2520, 1400], [2580, 1400]], pill: [2200, 1350], lines: ['links at', 'pinned commit'] },
        'c-app-c-xdc': { pts: [[1180, 980], [1574, 980], [1600, 980], [1600, 700], [1630, 700]], pill: [1350, 980], lines: ['same merge algo,', 'no runtime link'] },
        'p-dev-c-app': { pts: [[620, 880], [680, 880], [680, 920], [1180, 920]], pill: [900, 920], lines: ['builds + tests', '(Gradle)'] },
        'p-dev-c-feedtool': { pts: [[300, 1070], [300, 1110], [650, 1110], [650, 1990], [1700, 1990], [1700, 1820]], pill: [1100, 1980], lines: ['feed JSON in,', 'IFW1 payload out'] },
        'p-owner-s-intodo': { pts: [[1050, 420], [1050, 500]], pill: [1050, 462], lines: ['uses + reviews'] },
        'p-members-s-intodo': { pts: [[2150, 420], [2150, 500]], pill: [2150, 462], lines: ['edit shared list'] },
        'p-dev-x-gha': { pts: [[620, 1000], [716, 1000], [716, 2050], [3080, 2050], [3080, 940], [3060, 940]], pill: [2300, 2075], lines: ['triggers release +', 'render-check (manual)'] },
        'x-gha-x-github': { pts: [[2820, 1050], [2820, 1270]], pill: [2820, 1160], lines: ['signs + publishes', 'APK (manual)'] },
        'p-dev-x-files': { pts: [[380, 1070], [380, 1270]], pill: [300, 1200], lines: ['encrypts feed,', 'publishes by hand'] },
    };
    const grpRels: RelGeom[] = [...C4_RELS, ...GRP_EXTRA_RELS].map((rel) => {
        const a = AUTH_GRP[`${rel.a}-${rel.b}`];
        return { key: `${rel.a}-${rel.b}`, pts: a.pts.map(([x, y]) => ({ x, y })), bx: a.pill[0], by: a.pill[1], r: 220, label: a.lines.join(' '), lines: a.lines, hw: 220, hh: a.lines.length > 1 ? 55 : 38, dashed: rel.dashed, hidden: false, auth: true };
    });
    // Flyover crossings: where two authored routes cross, the later-drawn
    // edge arcs over the earlier one (circuit-diagram flyover). Crossings
    // hidden under a visible label pill need no hop - the pill covers them.
    const grpHops = new Map<string, Pos[]>();
    {
        const pillRs = grpRels.filter((g) => !g.hidden).map((g) => ({ x: g.bx - (g.hw ?? g.r), y: g.by - (g.hh ?? g.r), w: (g.hw ?? g.r) * 2, h: (g.hh ?? g.r) * 2 }));
        for (let i = 0; i < grpRels.length; i++) for (let j = i + 1; j < grpRels.length; j++) {
            const A = grpRels[i], B = grpRels[j];
            for (let a = 0; a + 1 < A.pts.length; a++) for (let b = 0; b + 1 < B.pts.length; b++) {
                const x = segX(A.pts[a], A.pts[a + 1], B.pts[b], B.pts[b + 1]);
                if (!x) continue;
                if (pillRs.some((r) => x.x > r.x && x.x < r.x + r.w && x.y > r.y && x.y < r.y + r.h)) continue;
                if (!grpHops.has(B.key)) grpHops.set(B.key, []);
                grpHops.get(B.key)!.push(x);
            }
        }
    }
    const sysRels = routeRels(SYS_RELS, sysLayout, cam.z, layRect, false, [...groupLayout, ...Object.entries(labelRects).map(([id, r]) => ({ g: { id: `lab-${id}`, label: '', sub: '', kind: 'system' as const, frac: { fx: 0, fy: 0 } }, x: r.x + r.w / 2, y: r.y + r.h / 2, hw: r.w / 2, hh: r.h / 2 }))]);
    // System band stays sparse: external/person
    // nodes render as kind-shaped markers only - the marker cluster is too
    // tight for clean name chips at that scale, so naming returns at the
    // container band and badges hide at this band (CSS); every relation
    // stays listed in the docked key.
    // Supporting views (dynamic flow / deployment) reuse the same
    // layout + routing pipeline; their edges are numbered steps/routes in
    // the docked key, same as the structure bands.
    const viewLayout: GroupNode[] = view !== 'structure' ? diagramLayout(VIEW_SETS[view].nodes as C4Node[], layRect, cam.z, measuredNodes) : [];
    const viewRelRoutes = view !== 'structure' ? routeRels(VIEW_SETS[view].rels as C4Rel[], viewLayout, cam.z, layRect, true) : [];
    // Dock key lists exactly the numbered set shown on the map at this band:
    // container band numbers C4_RELS then the extra system edges (same order
    // grpRels routes them); the system band shows unnumbered arrows, so its
    // key entries render without numbers below.
    const dockRels: C4Rel[] | null = view !== 'structure' ? VIEW_SETS[view].rels : band === 'grp' ? [...C4_RELS, ...GRP_EXTRA_RELS] : band === 'sys' ? SYS_RELS : null;

    const cards: Record<string, React.ReactNode> = {
        progress: <CardShell id="progress" tag="Center" purpose="Do the task - how-to" title="Review progress" centerCard onFocus={focusCard}>
            <p>{reviewedCount} of {SECTIONS.length} sections reviewed{flagCount > 0 ? ` - ${flagCount} flagged for another look` : ''}{findingsCount > 0 ? ` - ${findingsCount} with findings noted` : ''}.</p>
            <p className="rc-muted">Your marks and findings are saved on this device only. Clearing all marks starts the pass over.</p>
        </CardShell>,
        howto: <CardShell id="howto" tag="Start here" purpose="Do the task - how-to" title="How to review a section" onFocus={focusCard}>
            <ul>
                <li>Every card names its primary purpose (the Diataxis map): Understand views explain the architecture, Look up cards list the exact files at the pinned commit, Do the task cards run the review itself. Section cards intentionally mix reference and explanation inside those primary purposes.</li>
                <li>Pick a section card. Each is one piece of the repo, small enough for one sitting.</li>
                <li>Tap any file name - it opens that exact file at commit {COMMIT_SHORT}. Long lists unfold with "Show all files".</li>
                <li>Read, write what you found in the card's findings box, then mark it: Reviewed, or Needs another look.</li>
                <li>"Not checked yet" lists what nobody has verified, so gaps stay visible. A mark or a finding is your review act; neither claims the code is safe.</li>
                <li>No tutorial here on purpose: this surface supports review rather than a guided learning lesson.</li>
            </ul>
        </CardShell>,
        permanence: <CardShell id="permanence" tag="Permanence, honestly" purpose="Understand - explanation" title="What persists here" onFocus={focusCard}>
            <ul>
                <li>Your view, review marks and findings are remembered in this browser's local storage on this static page. Cards stay fixed in place.</li>
                <li>Not synced across devices; clearing browser storage resets them.</li>
                <li>Public repo content only, generated from irvan-putra/intodo @ {COMMIT_SHORT} on Nov 13, 2026; nothing is sent anywhere or published with this page, and nothing here changes the app or the repo.</li>
                <li>Marks made on the Instinct-hosted review page live in that browser's storage and cannot carry over here.</li>
                <li>Long-term ownership (export, backup) is an open question, not a claim.</li>
            </ul>
        </CardShell>,
        rule: <CardShell id="rule" tag="The honest rule" purpose="Understand - explanation" title="No hidden gaps" onFocus={focusCard}>
            <p>No card ever reads "this code is safe". Each card says exactly what it is, and what has not been checked stays visible in the same size next to it.</p>
        </CardShell>,
    };
    for (const s of SECTIONS) {
        cards[s.id] = <SectionCard key={s.id} s={s} mark={reviews[s.id]} finding={findings[s.id]} onFinding={recordFinding} expanded={expandedId === s.id} onToggleExpand={(id) => {
            const next = expandedId === id ? null : id;
            setExpandedId(next);
            // expanding the focused card changes its measured height; refocus
            // after the re-render so it still fits in the detail viewport
            if (next && focusedId === id) requestAnimationFrame(() => requestAnimationFrame(() => focusCard(id)));
        }} onMark={markSection} onFocus={focusCard} />;
    }

    return <>
        <div className="rc-page">
        <div
            ref={vpRef}
            className={`rc-viewport${dragging ? ' rc-dragging' : ''}${panning ? ' rc-panning' : ''} rc-band-${band}${band === 'sys' ? ' rc-sys' : band === 'grp' ? ' rc-grp' : band === 'lo' ? ' rc-lo' : ''}`}
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
                {view === 'structure' && (<>
                <div className="rc-groups">
                    {/* containers render as their (collapsed) frames, so only
                        externals/people appear as nodes here */}
                    {groupLayout.filter((n) => n.g.kind === 'external' || n.g.kind === 'person').map((n) => {
                        const g = n.g;
                        // box matches the routed node rect (same size as the
                        // system band) and the label font is capped so its
                        // longest word always fits inside the box
                        const nftmax = Math.floor((n.hw * 2 - 52) / (Math.max(...g.label.split(/\s+/).map((w) => w.length)) * 0.64));
                        return (
                            <div key={g.id} data-nid={g.id} className={`rc-grpnode rc-node-${g.kind}`} style={{ left: n.x, top: n.y, width: n.hw * 2, ['--nftmax' as string]: nftmax + 'px' }}>
                                {g.kind === 'person' ? C4_PERSON_ICON : null}
                                <span className="rc-grplabel">{g.label}</span>
                                <span className="rc-grpcount">{g.sub}</span>
                                <span className={`rc-kindtag rc-kindtag-${g.kind}`}>{KIND_TAG[g.kind]}</span>
                            </div>
                        );
                    })}
                </div>

                {/* system context level: people + the intodo system + external
                    systems, furthest zoomed out. Tapping the system drops
                    into the container level. */}
                <svg className="rc-sysedges" width={WORLD.w} height={WORLD.h}>
                    {/* markers must live in THIS svg: url(#...) into the
                        display:none container-band svg does not paint */}
                    <defs>
                        {/* System band keeps arrowheads proportional to the
                            11px-screen markers (the
                            default 7-stroke-wide heads pile over the tight
                            far-zoom node cluster). ~4.2 stroke widths reads
                            as a direction cue, not a second node. */}
                        <marker id="rc-arr-sys" markerWidth="4.2" markerHeight="4.2" refX="3.6" refY="2.1" orient="auto">
                            <path d="M0,0 L4.2,2.1 L0,4.2 z" fill="var(--ds-teal)" />
                        </marker>
                        <marker id="rc-arr-dash-sys" markerWidth="4.2" markerHeight="4.2" refX="3.6" refY="2.1" orient="auto">
                            <path d="M0,0 L4.2,2.1 L0,4.2 z" fill="var(--ds-ink-2)" />
                        </marker>
                    </defs>
                    {sysRels.map((g) => (
                        <polyline key={g.key} points={g.pts.map((p) => `${p.x},${p.y}`).join(' ')} fill="none"
                            className={g.dashed ? 'rc-rel-dash' : undefined}
                            markerEnd={`url(#${g.dashed ? 'rc-arr-dash-sys' : 'rc-arr-sys'})`} />
                    ))}
                </svg>
                <div className="rc-sysnodes">
                    {/* system band: people/external systems render as real
                        labeled boxes of comparable weight to the system box
                        (C4 System Context style);
                        detail still arrives with zoom, not with dots. */}
                    {sysLayout.map((n) => {
                        const g = n.g;
                        if (g.kind !== 'system') {
                            return (
                                <div key={g.id} data-nid={g.id} className={`rc-grpnode rc-node-${g.kind}`} style={{ left: n.x, top: n.y, width: n.hw * 2 }}>
                                    {g.kind === 'person' ? C4_PERSON_ICON : null}
                                    <span className="rc-grplabel">{g.label}</span>
                                    <span className="rc-grpcount">{g.sub}</span>
                                    <span className={`rc-kindtag rc-kindtag-${g.kind}`}>{KIND_TAG[g.kind]}</span>
                                </div>
                            );
                        }
                        const inner = <>
                            <span className="rc-grplabel">{g.label}</span>
                            <span className="rc-grpcount">{g.sub}</span>
                            <span className={`rc-kindtag rc-kindtag-${g.kind}`}>{KIND_TAG[g.kind]}</span>
                        </>;
                        // not a control: the boundary is territory, tapping it
                        // must not zoom (no clickables in the map)
                        return (
                            <div key={g.id} data-nid={g.id} className={`rc-grpnode rc-node-${g.kind} rc-node-boundary`} style={{ left: n.x, top: n.y, width: n.hw * 2, height: n.hh * 2 }}>{inner}</div>
                        );
                    })}
                </div>
                <svg className="rc-syslabels" width={WORLD.w} height={WORLD.h}>
                    {sysRels.map((g, i) => (g.hidden ? null : (
                        <g key={g.key} className="rc-rel-badge">
                            <circle cx={g.bx} cy={g.by} r={g.r} />
                            <text x={g.bx} y={g.by}>{i + 1}</text>
                        </g>
                    )))}
                </svg>
                </>)}
                {/* supporting views: dynamic flow and
                    deployment, rendered with the same nodes/edges/badges
                    machinery at any zoom; no section cards in these views */}
                {view !== 'structure' && (<>
                <svg className="rc-viewedges" width={WORLD.w} height={WORLD.h}>
                    <defs>
                        <marker id="rc-arr-view" markerWidth="7" markerHeight="7" refX="6" refY="3.5" orient="auto">
                            <path d="M0,0 L7,3.5 L0,7 z" fill="var(--ds-teal)" />
                        </marker>
                    </defs>
                    {viewRelRoutes.map((g) => (
                        <polyline key={g.key} points={g.pts.map((p) => `${p.x},${p.y}`).join(' ')} fill="none"
                            markerEnd="url(#rc-arr-view)" />
                    ))}
                </svg>
                <div className="rc-viewnodes">
                    {viewLayout.map((n) => (
                        <div key={n.g.id} data-nid={n.g.id} className={`rc-grpnode rc-node-${n.g.kind}`} style={{ left: n.x, top: n.y }}>
                            {n.g.kind === 'person' ? C4_PERSON_ICON : null}
                            <span className="rc-grplabel">{n.g.label}</span>
                            <span className="rc-grpcount">{n.g.sub}</span>
                            <span className={`rc-kindtag rc-kindtag-${n.g.kind}`}>{KIND_TAG[n.g.kind]}</span>
                        </div>
                    ))}
                </div>
                <svg className="rc-viewlabels" width={WORLD.w} height={WORLD.h}>
                    {viewRelRoutes.map((g) => (g.hidden ? null : (
                        <g key={g.key} className={`rc-rel-badge${g.dashed ? ' rc-rel-badge-dash' : ''}`}>
                            <rect x={g.bx - (g.hw ?? g.r)} y={g.by - (g.hh ?? g.r)} width={(g.hw ?? g.r) * 2} height={(g.hh ?? g.r) * 2} rx={Math.min(g.hh ?? g.r, 8 / cam.z)} />
                            {(g.lines ?? []).map((ln, li) => (
                                <text key={li} x={g.bx} y={g.by + (li - ((g.lines ?? []).length - 1) / 2) * 11 / cam.z}>{ln}</text>
                            ))}
                        </g>
                    )))}
                </svg>
                </>)}
                {/* section level: every card belongs to a C4 container. In
                    the detail band each container renders as a labeled frame
                    with its cards inside (NEST), C4 zoom-in-context style;
                    in the section band chips flow under their container label
                    in reading order (LO_LAYOUT). */}
                {view === 'structure' && NEST_COLUMNS.map((col, ci) => (
                    <div key={ci} className="rc-nest-col" style={{ left: NEST_COL_X[ci], top: 560 }}>
                        {col.map((gid) => {
                            const g = C4_CONTAINERS.find((c) => c.id === gid)!;
                            // Per-frame title cap (world px): the label must
                            // never overflow its frame at any band, so the font
                            // shrinks until the FULL title fits the frame's
                            // inner text width (frame width minus label inset
                            // 36 and the worst-case horizontal padding 2x60)
                            // at ~0.62em per glyph - no ellipsis truncation.
                            const ftmax = Math.floor((NEST_FRAME_W(gid) - 36 - 120) / (g.label.length * 0.62));
                            return (
                                <div key={gid} data-frame={gid} className={`rc-frame${g.kind === 'support' ? ' rc-frame-support' : ''}`} style={{ width: NEST_FRAME_W(gid), height: NEST_EST.frames[gid].h, ['--ftmax' as string]: ftmax + 'px' }}
                                    >
                                    <div ref={(el) => { frameLabelRefs.current[gid] = el; }} className={`rc-frame-label${(band === 'lo' || band === 'detail') && (labelFit[gid] ?? 0) >= 3 ? ' rc-frame-tight' : ''}`} style={{ ['--lfscale' as string]: (band === 'lo' || band === 'detail') ? Math.pow(0.78, labelFit[gid] ?? 0) : 1 }}><span className="rc-frame-row1"><span className="rc-frame-title">{g.label}</span></span><span className="rc-frame-row2"><span className="rc-frame-sub">{g.sub}</span><span className={`rc-frame-kind rc-kindtag-${g.kind}`}>{KIND_TAG[g.kind]}</span><span className="rc-frame-count">{(() => { const n = (g.members?.length ?? 0) + (g.attached?.length ?? 0); return `${n} ${n === 1 ? 'item' : 'items'}`; })()}</span></span></div>
                                    <div className="rc-frame-cards">
                                        {/* legacy saved positions (draggable era) migrate into the frame
                                            as reading order - top to bottom, then left to right - instead
                                            of being dropped; cards without a saved position keep their
                                            curated order. Cards stay fixed. */}
                                        {[...(g.members ?? []), ...(g.attached ?? [])]
                                            .map((m, i) => ({ m, i, p: pos[m] }))
                                            .sort((a, b) => (a.p && b.p ? (a.p.y - b.p.y) || (a.p.x - b.p.x) : a.p ? -1 : b.p ? 1 : a.i - b.i))
                                            .map(({ m }) => (
                                                <div key={m} style={{ width: CARD_SIZES[m].w, flex: 'none' }}>{cards[m]}</div>
                                            ))}
                                    </div>
                                </div>
                            );
                        })}
                    </div>
                ))}
                {/* container-band edges + authored label pills render last so
                    they paint above the collapsed frames: each pill must stay
                    fully visible on its own connector (interior stubs cross
                    frame fill/header edges by design). Both svgs are
                    pointer-events:none, so frame taps still land. rc-grp only. */}
                {view === 'structure' && (<>
                <svg className="rc-gedges" width={WORLD.w} height={WORLD.h}>
                    <defs>
                        {/* arrowheads stay smaller than the shaft they tip:
                            standard ~9px screen, short-final-segment routes
                            get the small head so the line reads as a line */}
                        <marker id="rc-arr" markerWidth="5" markerHeight="5" refX="4.4" refY="2.5" orient="auto">
                            <path d="M0,0 L5,2.5 L0,5 z" fill="var(--ds-teal)" />
                        </marker>
                        <marker id="rc-arr-dash" markerWidth="5" markerHeight="5" refX="4.4" refY="2.5" orient="auto">
                            <path d="M0,0 L5,2.5 L0,5 z" fill="var(--ds-ink-2)" />
                        </marker>
                        <marker id="rc-arr-sm" markerWidth="3.2" markerHeight="3.2" refX="2.7" refY="1.6" orient="auto">
                            <path d="M0,0 L3.2,1.6 L0,3.2 z" fill="var(--ds-teal)" />
                        </marker>
                        <marker id="rc-arr-dash-sm" markerWidth="3.2" markerHeight="3.2" refX="2.7" refY="1.6" orient="auto">
                            <path d="M0,0 L3.2,1.6 L0,3.2 z" fill="var(--ds-ink-2)" />
                        </marker>
                    </defs>
                    {grpRels.map((g) => {
                        const lp = g.pts[g.pts.length - 1], pp = g.pts[g.pts.length - 2];
                        const sm = Math.hypot(lp.x - pp.x, lp.y - pp.y) * cam.z < 26;
                        return (
                            <path key={g.key} d={edgePathD(g.pts, grpHops.get(g.key) ?? [], cam.z)} fill="none"
                                data-pts={g.pts.map((p) => `${p.x},${p.y}`).join(' ')}
                                className={g.dashed ? 'rc-rel-dash' : undefined}
                                markerEnd={`url(#${g.dashed ? 'rc-arr-dash' : 'rc-arr'}${sm ? '-sm' : ''})`} />
                        );
                    })}
                </svg>
                <svg className="rc-glabels" width={WORLD.w} height={WORLD.h}>
                    {grpRels.map((g) => (g.hidden ? null : (
                        <g key={g.key} className={`rc-rel-badge${g.dashed ? ' rc-rel-badge-dash' : ''}${g.auth ? ' rc-rel-badge-auth' : ''}`}>
                            <rect x={g.bx - (g.hw ?? g.r)} y={g.by - (g.hh ?? g.r)} width={(g.hw ?? g.r) * 2} height={(g.hh ?? g.r) * 2} rx={g.auth ? 26 : Math.min(g.hh ?? g.r, 8 / cam.z)} />
                            {(g.lines ?? []).map((ln, li) => (
                                <text key={li} x={g.bx} y={g.by + (li - ((g.lines ?? []).length - 1) / 2) * (g.auth ? 46 : 11 / cam.z)}>{ln}</text>
                            ))}
                        </g>
                    )))}
                </svg>
                </>)}
            </div>
            {/* navigation chrome (zoom buttons, Fit, minimap) intentionally
                removed - pan and pinch remain; controls come
                back in a later iteration. */}
            {/* storage honesty without permanent chrome: the notice only
                appears when saving actually fails */}
            {savedMeta.ready && /^Not saved/.test(savedMeta.message) ? (
                <div className="rc-status rc-warn" aria-live="polite">{savedMeta.message}</div>
            ) : null}

        </div>
        {/* Bottom navigation: a compact dock bar that never covers the
            canvas; the key and the menu open as docked panels above the
            bar, shrinking the viewport instead of overlaying the diagram.
            Structure edges carry short text labels on the map; the key lists
            each full relation with its endpoints; dashed = designed
            but not active (manual run) or no deployed runtime link */}
        {dock === 'legend' && dockRels ? (
            <div className="rc-legend">
                {view === 'flow' || view === 'flowfeed' ? <span className="rc-legend-item rc-legend-key">Numbered steps, in order:</span> : null}
                {dockRels.map((r, i) => (
                    <span className="rc-legend-item" key={r.label}>{view === 'structure' ? <span className="rc-legend-endpoints">{`${REL_NODE_LABEL(r.a)} \u2192 ${REL_NODE_LABEL(r.b)}: `}</span> : <span className={`rc-legend-num${r.dashed ? ' rc-legend-num-dash' : ''}`}>{i + 1}</span>}{r.label}</span>
                ))}
                {view === 'structure' ? (
                    <span className="rc-legend-item rc-legend-key"><span className="rc-legend-line"></span>observed flow or manual step (repo-evidenced) · <span className="rc-legend-line rc-legend-dash"></span>{band === 'grp' ? 'no runtime link / manual run, not automatic' : 'manual run, not automatic (workflow_dispatch in repo)'}</span>
                ) : null}
                {view === 'structure' ? (
                    <span className="rc-legend-item rc-legend-key rc-legend-shapes">Shapes: <span className="rc-kindchip rc-kindchip-person">{C4_PERSON_ICON}person</span> <span className="rc-kindchip rc-kindchip-system">software system</span> <span className="rc-kindchip rc-kindchip-container">container</span> <span className="rc-kindchip rc-kindchip-external">external system</span> <span className="rc-kindchip rc-kindchip-support">supporting</span></span>
                ) : null}
            </div>
        ) : null}
        {dock === 'menu' ? (
            <div className="rc-menu">
                <span className="rc-menu-head">Diagram views</span>
                <div className="rc-menu-row">
                    <button type="button" className={`rc-menu-btn${view === 'structure' ? ' rc-menu-on' : ''}`} onClick={() => setView('structure')}>Structure</button>
                    <button type="button" className={`rc-menu-btn${view === 'flow' ? ' rc-menu-on' : ''}`} onClick={() => setView('flow')}>List edit flow</button>
                    <button type="button" className={`rc-menu-btn${view === 'flowfeed' ? ' rc-menu-on' : ''}`} onClick={() => setView('flowfeed')}>App feed flow</button>
                    <button type="button" className={`rc-menu-btn${view === 'deploy' ? ' rc-menu-on' : ''}`} onClick={() => setView('deploy')}>Where it runs</button>
                </div>
                {view === 'structure' ? (
                    <>
                        <span className="rc-menu-head">Jump to a container</span>
                        <div className="rc-menu-row">
                            {C4_CONTAINERS.map((g) => (
                                <button key={g.id} type="button" className="rc-menu-btn" onClick={() => { setDock('none'); focusGroup(g.id); }}>{g.label}</button>
                            ))}
                        </div>
                        <span className="rc-menu-head">Review a section</span>
                        <div className="rc-menu-row">
                            {C4_CONTAINERS.map((g) => (
                                <button key={'s' + g.id} type="button" className="rc-menu-btn" onClick={() => { setDock('none'); setSheetGroup(g.id); }}>{g.label}</button>
                            ))}
                        </div>
                    </>
                ) : null}
            </div>
        ) : null}
        <div className="rc-dockbar">
            <button type="button" className={`rc-dockchip${dock === 'menu' ? ' rc-dockchip-on' : ''}`} onClick={() => setDock(dock === 'menu' ? 'none' : 'menu')}>{dock === 'menu' ? 'Close menu' : 'Menu'}</button>
            <button type="button" className="rc-dockchip" onClick={() => { viewTouched.current = true; fitView(); }}>Fit map</button>
            {dockRels ? (
                <button type="button" className={`rc-dockchip${dock === 'legend' ? ' rc-dockchip-on' : ''}`} onClick={() => setDock(dock === 'legend' ? 'none' : 'legend')}>{dock === 'legend' ? 'Hide key' : view === 'flow' || view === 'flowfeed' ? 'Step key' : 'Relationship key'}</button>
            ) : null}
            {/* the band/view caption lives in the dock, never on the canvas:
                as a bottom-right overlay it covered the external nodes and
                their labels exactly where a full-map fit places them. The
                row is ALWAYS rendered (hidden but space-reserved in the
                detail band): appearing/disappearing would resize the canvas
                mid-gesture and retrigger the viewport fit, yanking the pan. */}
            {(() => {
                const cap = view === 'flow' ? 'Understand - a shared list edit, step by step' :
                    view === 'flowfeed' ? 'Understand - the app list feed, step by step' :
                    view === 'deploy' ? 'Understand - where intodo runs' :
                    band === 'sys' ? 'Understand - the system context' :
                    band === 'grp' ? 'Understand - the containers' :
                    band === 'detail' ? '' :
                    `Look up - the ${SECTIONS.length} sections`;
                const hide = cap === '';
                return <span className="rc-bandcap" style={hide ? { visibility: 'hidden' } : undefined} aria-hidden={hide}>{hide ? '\u00a0' : cap}</span>;
            })()}
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
        </div>
    </>;
}

// Full-screen canvas, FreeForm-style: the canvas IS the page. No page
// framing, no square viewport; controls float over the canvas. Onboarding and
// the honesty/scope disclosures live on world cards (howto, permanence, rule,
// progress) so they pan and zoom with everything else.
export function App() {
    return <Canvas />;
}
