// canvas-math.js - pure camera/layout math for the itdxdc review canvas.
// Port of the itdweb React canvas math (itdweb/src/App.tsx) with no DOM or
// framework dependencies, so the exact same code runs in webxdc webviews and
// in Node for tests. Mirror of the Android port
// (app/src/main/java/com/instinct/widget/canvas/CanvasMath.java) -
// divergence between the two is a bug.
//
// Camera model: cam = { x, y, z } is the world point at the viewport center
// plus zoom. screen = (world - cam.xy) * z + vp/2; world = cam.xy + (screen - vp/2) / z.

(function (global) {
  'use strict';

  const WORLD = { w: 1760, h: 1500 };
  const ZMIN = 0.15, ZMAX = 2.5;
  const MINI_W = 132, MINI_PAD = 4;
  const STORE_KEY = 'itdxdc-review-canvas.v1';

  const CARD_SIZES = {
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

  const DEFAULT_POS = {
    change: { x: 690, y: 430 },
    permanence: { x: 690, y: 60 },
    identity: { x: 160, y: 250 },
    reviews: { x: 1240, y: 250 },
    source: { x: 160, y: 620 },
    rule: { x: 1240, y: 620 },
    gaps: { x: 690, y: 820 },
    // bottom row sits below the gaps card's real rendered height (381px at the
    // fixed 380px width - taller than its 200px layout estimate), not the estimate,
    // with slack for font-metric differences across platforms
    property: { x: 40, y: 1240 },
    mutation: { x: 400, y: 1240 },
    fuzz: { x: 760, y: 1240 },
    load: { x: 1120, y: 1240 },
    smoke: { x: 1420, y: 1240 },
  };

  const EDGES = [
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

  const clampZ = (z) => Math.min(ZMAX, Math.max(ZMIN, z));
  const clampCam = (c) => ({
    x: Math.min(WORLD.w + 120, Math.max(-120, c.x)),
    y: Math.min(WORLD.h + 120, Math.max(-120, c.y)),
    z: c.z,
  });

  const center = (id, pos) => {
    const s = CARD_SIZES[id];
    return { x: pos[id].x + s.w / 2, y: pos[id].y + s.h / 2 };
  };

  const toWorld = (p, cam, vp) => ({
    x: cam.x + (p.x - vp.w / 2) / cam.z,
    y: cam.y + (p.y - vp.h / 2) / cam.z,
  });

  const toScreen = (w, cam, vp) => ({
    x: (w.x - cam.x) * cam.z + vp.w / 2,
    y: (w.y - cam.y) * cam.z + vp.h / 2,
  });

  // Fit the whole world into the viewport with a 2% margin.
  const fitCam = (vpW, vpH) => ({
    x: WORLD.w / 2,
    y: WORLD.h / 2,
    z: clampZ(Math.min(vpW / WORLD.w, vpH / WORLD.h) * 0.98),
  });

  // Center a card and zoom so it (plus 80px slack) fills the width, capped at 1.2.
  const focusCam = (id, pos, vpW) => {
    const ctr = center(id, pos);
    return clampCam({ x: ctr.x, y: ctr.y, z: clampZ(Math.min(vpW / (CARD_SIZES[id].w + 80), 1.2)) });
  };

  const zoomCam = (cam, f) => clampCam({ x: cam.x, y: cam.y, z: clampZ(cam.z * f) });

  // Pan by a screen-space delta (the world follows the pointer).
  const panCam = (cam, dxScreen, dyScreen) =>
    clampCam({ x: cam.x - dxScreen / cam.z, y: cam.y - dyScreen / cam.z, z: cam.z });

  // Anchored pinch zoom: the world point under the pinch midpoint at gesture
  // start (pinch = { d0, z0, cam0 }) stays under the current midpoint.
  const pinchCam = (pinch, mid, d, vp) => {
    const z = clampZ(pinch.z0 * (d / (pinch.d0 || 1)));
    const mw = toWorld(mid, pinch.cam0, vp);
    return clampCam({ x: mw.x - (mid.x - vp.w / 2) / z, y: mw.y - (mid.y - vp.h / 2) / z, z });
  };

  // Drag a card: worldPt is the pointer in world space, off the grab offset.
  const dragPos = (pos, id, worldPt, off) => ({
    ...pos,
    [id]: { x: Math.round(worldPt.x - off.x), y: Math.round(worldPt.y - off.y) },
  });

  const miniScale = () => (MINI_W - MINI_PAD * 2) / WORLD.w;
  const miniHeight = () => Math.round(WORLD.h * miniScale()) + MINI_PAD * 2;

  const miniViewRect = (cam, vp) => ({
    left: MINI_PAD + (cam.x - vp.w / (2 * cam.z)) * miniScale(),
    top: MINI_PAD + (cam.y - vp.h / (2 * cam.z)) * miniScale(),
    width: (vp.w / cam.z) * miniScale(),
    height: (vp.h / cam.z) * miniScale(),
  });

  const camFromMiniPoint = (mx, my, cam) =>
    clampCam({ x: (mx - MINI_PAD) / miniScale(), y: (my - MINI_PAD) / miniScale(), z: cam.z });

  const defaultCam = () => ({ x: WORLD.w / 2, y: WORLD.h / 2, z: 0.25 });
  const defaultPos = () => {
    const out = {};
    for (const id of Object.keys(DEFAULT_POS)) out[id] = { ...DEFAULT_POS[id] };
    return out;
  };

  const finite = (n) => typeof n === 'number' && Number.isFinite(n);

  // Validate/repair a persisted layout. Never throws: structural garbage
  // returns null (caller starts fresh), per-field problems are repaired -
  // unknown card ids dropped, missing ids restored to defaults, zoom and
  // camera position clamped. (The itdweb React original did not clamp the
  // loaded camera; both native ports do - stored state is untrusted input.)
  function sanitizeSaved(raw) {
    if (raw === null || raw === undefined) return null;
    if (typeof raw !== 'object' || Array.isArray(raw)) return null;
    if (raw.cam !== undefined && (raw.cam === null || typeof raw.cam !== 'object')) return null;
    if (raw.pos !== undefined && (raw.pos === null || typeof raw.pos !== 'object' || Array.isArray(raw.pos))) return null;
    let cam = defaultCam();
    if (raw.cam !== undefined) {
      if (!finite(raw.cam.x) || !finite(raw.cam.y) || !finite(raw.cam.z)) return null;
      // clamp on load too: stored values are not under our control
      cam = clampCam({ x: raw.cam.x, y: raw.cam.y, z: clampZ(raw.cam.z) });
    }
    const pos = defaultPos();
    if (raw.pos !== undefined) {
      for (const id of Object.keys(raw.pos)) {
        if (!Object.prototype.hasOwnProperty.call(DEFAULT_POS, id)) continue; // drop unknown ids
        const p = raw.pos[id];
        if (p && typeof p === 'object' && finite(p.x) && finite(p.y)) pos[id] = { x: p.x, y: p.y };
      }
    }
    return { cam, pos };
  }

  const serializeSaved = (state) => JSON.stringify({ cam: state.cam, pos: state.pos });

  function parseSaved(str) {
    if (typeof str !== 'string') return null;
    let raw;
    try {
      raw = JSON.parse(str);
    } catch (e) {
      return null;
    }
    return sanitizeSaved(raw);
  }

  global.CANVAS_WORLD = WORLD;
  global.CANVAS_ZMIN = ZMIN;
  global.CANVAS_ZMAX = ZMAX;
  global.CANVAS_MINI_W = MINI_W;
  global.CANVAS_MINI_PAD = MINI_PAD;
  global.CANVAS_STORE_KEY = STORE_KEY;
  global.CANVAS_CARD_SIZES = CARD_SIZES;
  global.CANVAS_DEFAULT_POS = DEFAULT_POS;
  global.CANVAS_EDGES = EDGES;
  global.CanvasMath = {
    clampZ, clampCam, center, toWorld, toScreen, fitCam, focusCam, zoomCam,
    panCam, pinchCam, dragPos, miniScale, miniHeight, miniViewRect,
    camFromMiniPoint, defaultCam, defaultPos, sanitizeSaved, serializeSaved, parseSaved,
  };
})(typeof window !== 'undefined' ? window : globalThis);
