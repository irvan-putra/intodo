// Property tests for the JS port of MergeDoc - mirrors MergeDocTest.java:
// convergence (shuffled + duplicated delivery, skewed clocks), idempotence,
// merge commutativity + associativity, deterministic tie-break, tombstone
// semantics, conflict heuristic. Run: node --test test/
import { test } from 'node:test';
import assert from 'node:assert/strict';
import '../js/mergedoc.js';

const MergeDoc = globalThis.MergeDoc;
const SKEW = globalThis.MERGE_SKEW_WINDOW_MS;
const FIELDS = ['title', 'note', 'due', 'done'];
const DEVICES = ['phone-a', 'phone-b', 'friend-x'];

// deterministic PRNG (mulberry32) so seeds are reproducible across runs
function rng(seed) {
  let a = seed >>> 0;
  return function () {
    a |= 0; a = (a + 0x6D2B79F5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function randomOps(seed, count) {
  const rnd = rng(seed);
  const skew = [0, 120000, -300000]; // device clock offsets, ms
  const ops = [];
  const base = 1700000000000;
  for (let i = 0; i < count; i++) {
    const dev = Math.floor(rnd() * DEVICES.length);
    const item = 'item-' + Math.floor(rnd() * 4);
    const field = Math.floor(rnd() * 10) === 0 ? 'deleted' : FIELDS[Math.floor(rnd() * FIELDS.length)];
    const value = field === 'deleted' || field === 'done' ? String(rnd() < 0.5) : 'v' + Math.floor(rnd() * 1000);
    const phys = base + Math.floor(rnd() * 3600000);
    ops.push({ item, field, value, stamp: { hlc: phys + skew[dev], device: DEVICES[dev] } });
  }
  return ops;
}

function replicaFrom(ops, seed, duplicate) {
  const shuffled = [...ops];
  const rnd = rng(seed);
  for (let i = shuffled.length - 1; i > 0; i--) {
    const j = Math.floor(rnd() * (i + 1));
    [shuffled[i], shuffled[j]] = [shuffled[j], shuffled[i]];
  }
  const delivery = duplicate ? shuffled.concat(shuffled.slice(0, shuffled.length >> 1)) : shuffled;
  const d = new MergeDoc();
  for (const op of delivery) d.apply(op.item, op.field, op.value, op.stamp);
  return d;
}

test('converges across order and duplicates with skewed clocks', () => {
  for (let seed = 1; seed <= 50; seed++) {
    const ops = randomOps(seed, 300);
    const a = replicaFrom(ops, seed * 3 + 1, true);
    const b = replicaFrom(ops, seed * 3 + 2, true);
    const c = replicaFrom(ops, seed * 3 + 3, false);
    assert.ok(a.equals(b), 'seed ' + seed);
    assert.ok(b.equals(c), 'seed ' + seed);
  }
});

test('idempotent', () => {
  const d = new MergeDoc();
  const s = { hlc: 100, device: 'phone-a' };
  d.apply('i1', 'title', 'hello', s);
  const once = d.copy();
  d.apply('i1', 'title', 'hello', s);
  assert.ok(once.equals(d));
  assert.equal(once.history('i1').length, 0);
});

test('merge is commutative and associative', () => {
  for (let seed = 100; seed <= 120; seed++) {
    const ops = randomOps(seed, 120);
    const a = replicaFrom(ops.slice(0, 40), seed, false);
    const b = replicaFrom(ops.slice(40, 80), seed + 1, false);
    const c = replicaFrom(ops.slice(80, 120), seed + 2, false);
    const ab = a.copy(); ab.merge(b);
    const ba = b.copy(); ba.merge(a);
    assert.ok(ab.equals(ba), 'commutative seed ' + seed);
    const abC = ab.copy(); abC.merge(c);
    const bc = b.copy(); bc.merge(c);
    const aBc = a.copy(); aBc.merge(bc);
    assert.ok(abC.equals(aBc), 'associative seed ' + seed);
  }
});

test('tie-break by deviceId is deterministic', () => {
  const d1 = new MergeDoc();
  d1.apply('i1', 'done', 'true', { hlc: 50, device: 'phone-a' });
  d1.apply('i1', 'done', 'false', { hlc: 50, device: 'phone-b' });
  const d2 = new MergeDoc();
  d2.apply('i1', 'done', 'false', { hlc: 50, device: 'phone-b' });
  d2.apply('i1', 'done', 'true', { hlc: 50, device: 'phone-a' });
  assert.ok(d1.equals(d2));
  assert.equal(d1.get('i1', 'done'), 'false'); // phone-b wins: lexical deviceId tie-break
});

test('delete is tombstone; late edit does not resurrect; explicit revive does', () => {
  const d = new MergeDoc();
  d.apply('i1', 'title', 'task', { hlc: 10, device: 'phone-a' });
  d.apply('i1', 'deleted', 'true', { hlc: 20, device: 'phone-b' });
  assert.equal(d.isVisible('i1'), false);
  d.apply('i1', 'title', 'task edited late', { hlc: 15, device: 'phone-a' });
  assert.equal(d.isVisible('i1'), false);
  assert.equal(d.get('i1', 'title'), 'task edited late');
  d.apply('i1', 'deleted', 'false', { hlc: 30, device: 'phone-a' });
  assert.equal(d.isVisible('i1'), true);
});

test('conflicts surface cross-device near-simultaneous losers only', () => {
  const d = new MergeDoc();
  d.apply('i1', 'title', 'from-a', { hlc: 1000, device: 'phone-a' });
  d.apply('i1', 'title', 'from-b', { hlc: 1060, device: 'phone-b' });
  assert.equal(d.conflicts('i1').length, 1);
  assert.equal(d.conflicts('i1')[0].value, 'from-a');
  const solo = new MergeDoc();
  solo.apply('i1', 'title', 'typo', { hlc: 1000, device: 'phone-a' });
  solo.apply('i1', 'title', 'fixed', { hlc: 1010, device: 'phone-a' });
  assert.equal(solo.conflicts('i1').length, 0);
  const seq = new MergeDoc();
  seq.apply('i1', 'title', 'from-a', { hlc: 1000, device: 'phone-a' });
  seq.apply('i1', 'title', 'from-b', { hlc: 1000 + SKEW + 1, device: 'phone-b' });
  assert.equal(seq.conflicts('i1').length, 0);
});

test('losers stay in history and converge', () => {
  const a = new MergeDoc();
  a.apply('i1', 'title', 'from-a', { hlc: 10, device: 'phone-a' });
  a.apply('i1', 'title', 'from-b', { hlc: 20, device: 'phone-b' });
  const b = new MergeDoc();
  b.apply('i1', 'title', 'from-b', { hlc: 20, device: 'phone-b' });
  b.apply('i1', 'title', 'from-a', { hlc: 10, device: 'phone-a' });
  assert.ok(a.equals(b));
  assert.equal(a.get('i1', 'title'), 'from-b');
  assert.equal(a.history('i1').length, 1);
  assert.equal(a.history('i1')[0].value, 'from-a');
});

test('serialize/deserialize round-trips and converges', () => {
  const ops = randomOps(9, 150);
  const a = replicaFrom(ops, 1, true);
  const restored = MergeDoc.deserialize(a.serialize());
  assert.ok(a.equals(restored));
  const b = replicaFrom(ops, 2, false);
  assert.ok(b.equals(restored));
});

// Simulated webxdc exchange: two instances sharing ONLY serialized updates,
// as the messaging layer would deliver them (permuted + duplicated).
test('two webxdc-style instances converge through update exchange', () => {
  const ops = randomOps(777, 200);
  const updates = ops.map(op => ({ op })); // update payloads
  const instA = new MergeDoc();
  const instB = new MergeDoc();
  const rnd = rng(42);
  const shuffled = [...updates];
  for (let i = shuffled.length - 1; i > 0; i--) {
    const j = Math.floor(rnd() * (i + 1));
    [shuffled[i], shuffled[j]] = [shuffled[j], shuffled[i]];
  }
  for (const u of shuffled.concat(shuffled.slice(0, 50))) { // duplicates included
    instA.apply(u.op.item, u.op.field, u.op.value, u.op.stamp);
    instB.apply(u.op.item, u.op.field, u.op.value, u.op.stamp);
  }
  assert.ok(instA.equals(instB));
});
