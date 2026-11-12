// Property tests for the review-canvas camera math (js/canvas-math.js) -
// mirrors CanvasMathTest.java in the Android port. Covers: camera invariants
// under random gesture sequences, fit/focus geometry, pinch anchor stability,
// minimap round-trip, persistence sanitize/fuzz robustness.
// Run: node --test test/
import { test } from 'node:test';
import assert from 'node:assert/strict';
import '../js/canvas-math.js';

const M = globalThis.CanvasMath;
const WORLD = globalThis.CANVAS_WORLD;
const ZMIN = globalThis.CANVAS_ZMIN;
const ZMAX = globalThis.CANVAS_ZMAX;
const CARD_SIZES = globalThis.CANVAS_CARD_SIZES;
const IDS = Object.keys(CARD_SIZES);
const EPS = 1e-9;

function rng(seed) {
  let a = seed >>> 0;
  return function () {
    a |= 0; a = (a + 0x6D2B79F5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const near = (a, b, eps = 1e-6) => Math.abs(a - b) <= eps * Math.max(1, Math.abs(a), Math.abs(b));

function assertCamValid(cam) {
  assert.ok(Number.isFinite(cam.x) && Number.isFinite(cam.y) && Number.isFinite(cam.z), 'camera finite');
  assert.ok(cam.z >= ZMIN - EPS && cam.z <= ZMAX + EPS, 'zoom within bounds');
  assert.ok(cam.x >= -120 - EPS && cam.x <= WORLD.w + 120 + EPS, 'cam x clamped');
  assert.ok(cam.y >= -120 - EPS && cam.y <= WORLD.h + 120 + EPS, 'cam y clamped');
}

test('screen/world round-trip for random cameras and points', () => {
  const rnd = rng(1);
  for (let i = 0; i < 2000; i++) {
    const cam = { x: (rnd() - 0.5) * 3 * WORLD.w, y: (rnd() - 0.5) * 3 * WORLD.h, z: ZMIN + rnd() * (ZMAX - ZMIN) };
    const vp = { w: 100 + rnd() * 1200, h: 100 + rnd() * 900 };
    const p = { x: rnd() * vp.w, y: rnd() * vp.h };
    const back = M.toScreen(M.toWorld(p, cam, vp), cam, vp);
    assert.ok(near(back.x, p.x) && near(back.y, p.y), `round-trip ${i}`);
  }
});

test('fit shows the whole world whenever zoom is not clamped', () => {
  const rnd = rng(2);
  for (let i = 0; i < 500; i++) {
    const vp = { w: 80 + rnd() * 2000, h: 80 + rnd() * 1400 };
    const cam = M.fitCam(vp.w, vp.h);
    assertCamValid(cam);
    const idealZ = Math.min(vp.w / WORLD.w, vp.h / WORLD.h) * 0.98;
    if (idealZ >= ZMIN && idealZ <= ZMAX) {
      for (const c of [{ x: 0, y: 0 }, { x: WORLD.w, y: 0 }, { x: 0, y: WORLD.h }, { x: WORLD.w, y: WORLD.h }]) {
        const s = M.toScreen(c, cam, vp);
        assert.ok(s.x >= -EPS && s.x <= vp.w + EPS && s.y >= -EPS && s.y <= vp.h + EPS, `corner visible ${i}`);
      }
    }
  }
});

test('focus centers the card at the viewport midpoint', () => {
  const rnd = rng(3);
  for (let i = 0; i < 500; i++) {
    const id = IDS[Math.floor(rnd() * IDS.length)];
    const pos = M.defaultPos();
    pos[id] = { x: rnd() * WORLD.w, y: rnd() * WORLD.h };
    const vpW = 200 + rnd() * 1200;
    const vpH = 200 + rnd() * 900;
    const cam = M.focusCam(id, pos, vpW);
    assertCamValid(cam);
    // exact centering holds when the card center is inside the camera clamp range
    const ctr = M.center(id, pos);
    if (ctr.x >= -120 && ctr.x <= WORLD.w + 120 && ctr.y >= -120 && ctr.y <= WORLD.h + 120) {
      const s = M.toScreen(ctr, cam, { w: vpW, h: vpH });
      assert.ok(near(s.x, vpW / 2, 1e-6), `focus x ${id}`);
      assert.ok(near(s.y, vpH / 2, 1e-6), `focus y ${id}`);
    }
  }
});

test('pinch keeps the anchor world point under the midpoint', () => {
  const rnd = rng(4);
  for (let i = 0; i < 2000; i++) {
    const vp = { w: 300 + rnd() * 1000, h: 300 + rnd() * 800 };
    const cam0 = { x: WORLD.w / 2, y: WORLD.h / 2, z: ZMIN + rnd() * (ZMAX - ZMIN) };
    const d0 = 50 + rnd() * 300;
    const pinch = { d0, z0: cam0.z, cam0 };
    const mid = { x: rnd() * vp.w, y: rnd() * vp.h };
    const d = 20 + rnd() * 500;
    const cam1 = M.pinchCam(pinch, mid, d, vp);
    assertCamValid(cam1);
    const before = M.toWorld(mid, cam0, vp);
    const after = M.toWorld(mid, cam1, vp);
    // guards precomputed from inputs, never from the function under test
    const expZ = Math.min(ZMAX, Math.max(ZMIN, cam0.z * (d / d0)));
    const zUnclamped = near(expZ, cam0.z * (d / d0), 1e-6);
    const ux = before.x - (mid.x - vp.w / 2) / expZ, uy = before.y - (mid.y - vp.h / 2) / expZ;
    const xyUnclamped = ux >= -120 && ux <= WORLD.w + 120 && uy >= -120 && uy <= WORLD.h + 120;
    if (zUnclamped && xyUnclamped) {
      assert.ok(near(cam1.z, expZ, 1e-6), `pinch zoom ${i}`);
      assert.ok(near(before.x, after.x, 1e-6) && near(before.y, after.y, 1e-6), `anchor stable ${i}`);
    }
  }
});

test('pan moves every world point by the same screen delta', () => {
  const rnd = rng(5);
  for (let i = 0; i < 1000; i++) {
    const vp = { w: 400, h: 600 };
    const cam = { x: WORLD.w / 2, y: WORLD.h / 2, z: ZMIN + rnd() * (ZMAX - ZMIN) };
    const dx = (rnd() - 0.5) * 400, dy = (rnd() - 0.5) * 400;
    const cam2 = M.panCam(cam, dx, dy);
    const w = { x: rnd() * WORLD.w, y: rnd() * WORLD.h };
    const s1 = M.toScreen(w, cam, vp);
    const s2 = M.toScreen(w, cam2, vp);
    // interior case must be exact; guard precomputed from inputs, not from the function under test
    const expX = cam.x - dx / cam.z, expY = cam.y - dy / cam.z;
    const interior = expX >= -120 && expX <= WORLD.w + 120 && expY >= -120 && expY <= WORLD.h + 120;
    if (interior) {
      assert.ok(near(cam2.x, expX, 1e-6) && near(cam2.y, expY, 1e-6), `pan cam ${i}`);
      assert.ok(near(s2.x, s1.x + dx, 1e-6) && near(s2.y, s1.y + dy, 1e-6), `pan delta ${i}`);
    }
  }
});

test('zoom scales screen distance from the viewport center', () => {
  const rnd = rng(6);
  for (let i = 0; i < 1000; i++) {
    const vp = { w: 390, h: 700 };
    const cam = { x: 500 + rnd() * 800, y: 400 + rnd() * 600, z: ZMIN + rnd() * (ZMAX - ZMIN) };
    const f = 0.5 + rnd() * 2;
    const cam2 = M.zoomCam(cam, f);
    assertCamValid(cam2);
    const w = { x: rnd() * WORLD.w, y: rnd() * WORLD.h };
    const s1 = M.toScreen(w, cam, vp);
    const s2 = M.toScreen(w, cam2, vp);
    const expZ = Math.min(ZMAX, Math.max(ZMIN, cam.z * f)); // guard from inputs
    if (near(expZ, cam.z * f, 1e-6)) {
      assert.ok(near(cam2.z, expZ, 1e-6), `zoom applied ${i}`);
      assert.ok(near(s2.x - vp.w / 2, (s1.x - vp.w / 2) * f, 1e-6), `zoom x scale ${i}`);
      assert.ok(near(s2.y - vp.h / 2, (s1.y - vp.h / 2) * f, 1e-6), `zoom y scale ${i}`);
    }
  }
});

test('minimap: view-rect center maps back to the camera center', () => {
  const rnd = rng(7);
  for (let i = 0; i < 1000; i++) {
    const vp = { w: 100 + rnd() * 1200, h: 100 + rnd() * 900 };
    const cam = { x: rnd() * WORLD.w, y: rnd() * WORLD.h, z: ZMIN + rnd() * (ZMAX - ZMIN) };
    const r = M.miniViewRect(cam, vp);
    const back = M.camFromMiniPoint(r.left + r.width / 2, r.top + r.height / 2, cam);
    assert.ok(near(back.x, cam.x, 1e-6) && near(back.y, cam.y, 1e-6), `minimap round-trip ${i}`);
    assert.ok(near(back.z, cam.z), 'minimap keeps zoom');
  }
});

test('random gesture sequences keep the camera valid', () => {
  const rnd = rng(8);
  for (let run = 0; run < 50; run++) {
    const vp = { w: 320 + rnd() * 900, h: 320 + rnd() * 700 };
    let cam = M.fitCam(vp.w, vp.h);
    for (let step = 0; step < 200; step++) {
      const kind = Math.floor(rnd() * 4);
      if (kind === 0) cam = M.panCam(cam, (rnd() - 0.5) * 800, (rnd() - 0.5) * 800);
      else if (kind === 1) cam = M.zoomCam(cam, 0.3 + rnd() * 3);
      else if (kind === 2) cam = M.fitCam(vp.w, vp.h);
      else {
        const pinch = { d0: 30 + rnd() * 200, z0: cam.z, cam0: cam };
        cam = M.pinchCam(pinch, { x: rnd() * vp.w, y: rnd() * vp.h }, 10 + rnd() * 400, vp);
      }
      assertCamValid(cam);
    }
  }
});

test('drag moves a card by the world-space delta and rounds to integers', () => {
  const rnd = rng(9);
  for (let i = 0; i < 1000; i++) {
    const id = IDS[Math.floor(rnd() * IDS.length)];
    const pos = M.defaultPos();
    const off = { x: rnd() * 50, y: rnd() * 50 };
    const w = { x: rnd() * 2 * WORLD.w - 200, y: rnd() * 2 * WORLD.h - 200 };
    const out = M.dragPos(pos, id, w, off);
    assert.ok(Number.isInteger(out[id].x) && Number.isInteger(out[id].y), 'rounded');
    assert.ok(near(out[id].x, Math.round(w.x - off.x)), 'x delta');
    assert.ok(near(out[id].y, Math.round(w.y - off.y)), 'y delta');
    assert.equal(Object.keys(out).length, IDS.length, 'no cards lost');
    for (const other of IDS) if (other !== id) assert.deepEqual(out[other], pos[other]);
  }
});

test('sanitize repairs and parse/serialize round-trips', () => {
  const state = { cam: { x: 100, y: 200, z: 0.5 }, pos: M.defaultPos() };
  state.pos.change = { x: 42, y: 24 };
  const back = M.parseSaved(M.serializeSaved(state));
  assert.deepEqual(back, state);

  // zoom outside bounds is clamped, unknown ids dropped, missing ids restored
  const repaired = M.sanitizeSaved({
    cam: { x: 10, y: 20, z: 99 },
    pos: { change: { x: 1, y: 2 }, bogus: { x: 0, y: 0 }, fuzz: 'garbage' },
  });
  assert.equal(repaired.cam.z, ZMAX);
  assert.deepEqual(repaired.pos.change, { x: 1, y: 2 });
  assert.ok(!('bogus' in repaired.pos));
  assert.deepEqual(repaired.pos.fuzz, globalThis.CANVAS_DEFAULT_POS.fuzz);
  assert.equal(Object.keys(repaired.pos).length, IDS.length);
});

test('sanitize/parse never throw on fuzz garbage', () => {
  const rnd = rng(10);
  const junk = (depth = 0) => {
    const k = Math.floor(rnd() * 8);
    if (depth > 4 || k === 0) return null;
    if (k === 1) return rnd() * 1000 - 500;
    if (k === 2) return 'x'.repeat(Math.floor(rnd() * 20));
    if (k === 3) return rnd() < 0.5;
    if (k === 4) return [junk(depth + 1), junk(depth + 1)];
    if (k === 5) return { cam: junk(depth + 1), pos: junk(depth + 1) };
    if (k === 6) return { x: junk(depth + 1), y: junk(depth + 1), z: junk(depth + 1) };
    const o = {};
    for (let i = 0; i < Math.floor(rnd() * 5); i++) o['k' + i] = junk(depth + 1);
    return o;
  };
  for (let i = 0; i < 10000; i++) {
    const out = M.sanitizeSaved(junk()); // must not throw
    if (out !== null) {
      assertCamValid(out.cam);
      assert.equal(Object.keys(out.pos).length, IDS.length);
      for (const id of IDS) assert.ok(Number.isFinite(out.pos[id].x) && Number.isFinite(out.pos[id].y));
    }
  }
  // parseSaved on arbitrary strings
  for (let i = 0; i < 2000; i++) {
    const s = Math.floor(rnd() * 3) === 0 ? M.serializeSaved({ cam: { x: rnd() * 100, y: rnd() * 100, z: rnd() * 3 }, pos: M.defaultPos() }).slice(0, Math.floor(rnd() * 40)) : String(junk());
    const out = M.parseSaved(s); // must not throw
    if (out !== null) assertCamValid(out.cam);
  }
  assert.equal(M.parseSaved(null), null);
  assert.equal(M.parseSaved(''), null);
  assert.equal(M.parseSaved('{'), null);
  assert.equal(M.parseSaved('{"cam":null}'), null);
});
