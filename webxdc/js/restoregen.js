/* itd_restore_v2 app-side generation tracker.
 *
 * Tracks the LATEST incomplete restore generation: which batches arrived,
 * the accumulated ops, and completion gating (all batches + whole-generation
 * canonical digest match). Bounded: exactly one generation of coverage is
 * persisted (localStorage), dropped on completion. A newer generation
 * supersedes stored coverage; duplicate or out-of-order batches are safe.
 *
 * Env-agnostic: in the app it uses window.ItdDigest + localStorage; tests
 * inject both. applyOps is a caller callback (MergeDoc apply + persist).
 */
(function (root) {
  'use strict';

  var STORAGE_KEY = 'instinct-shared-list-restore-v1';
  // Latest VERIFIED-complete generation, kept SEPARATE from the incomplete
  // coverage (which is dropped on completion). Written only after the
  // whole-set digest matched, read into the boot hint so healthy opens
  // cost the bot nothing. It is a claim about THIS instance's own state,
  // never a gate for anyone else.
  var COMPLETE_KEY = 'instinct-shared-list-restore-complete-v1';
  var MAX_BATCH_COUNT = 1000;

  function isHex64(s) {
    return typeof s === 'string' && s.length === 64 &&
      /^[0-9a-f]{64}$/.test(s);
  }

  function opKey(op) {
    // op identity for accumulation dedup: (item, field, value, hlc, device);
    // JSON-tuple encoding - a bare join can collide across field boundaries
    var st = op && op.stamp || {};
    return JSON.stringify([op.item, op.field, op.value, st.hlc, st.device]);
  }

  function create(opts) {
    opts = opts || {};
    var digest = opts.digest || (root.ItdDigest);
    var storage = opts.storage ||
      (typeof root.localStorage !== 'undefined' ? root.localStorage : null);
    var state = null; // {generation_id, generation_digest, batch_count,
                      //  received: [], ops: [], seen: {}}

    function persist() {
      if (!storage) return;
      try {
        if (!state) { storage.removeItem(STORAGE_KEY); return; }
        storage.setItem(STORAGE_KEY, JSON.stringify({
          generation_id: state.generation_id,
          generation_digest: state.generation_digest,
          batch_count: state.batch_count,
          received: state.received,
          ops: state.ops,
          hint_attempts: state.hint_attempts || null,
        }));
      } catch (e) { /* storage full/blocked: in-memory state still works */ }
    }

    function load() {
      if (!storage) return;
      try {
        var raw = storage.getItem(STORAGE_KEY);
        if (!raw) return;
        var d = JSON.parse(raw);
        if (!d || !isHex64(d.generation_id) ||
            typeof d.batch_count !== 'number' ||
            !Array.isArray(d.received) || !Array.isArray(d.ops)) return;
        state = {
          generation_id: d.generation_id,
          generation_digest: d.generation_digest,
          batch_count: d.batch_count,
          received: d.received.filter(function (i) {
            return Number.isInteger(i) && i >= 0 && i < d.batch_count;
          }),
          ops: d.ops,
          seen: {},
          hint_attempts: d.hint_attempts &&
            typeof d.hint_attempts.count === 'number' &&
            typeof d.hint_attempts.window_start === 'number'
            ? d.hint_attempts : null,
        };
        state.received.forEach(function (i) { state.seen[i] = true; });
      } catch (e) { /* corrupt coverage: start clean */ state = null; }
    }

    /* Bounded resume-hint budget: maxPerWindow hints per generation per
     * windowMs, PERSISTED with the coverage so an app restart cannot reset
     * it (the bot enforces the same bound; this keeps the app from asking
     * into an exhausted window forever). One fresh window after the
     * cooldown, mirroring the bot. Returns true when a hint may be sent
     * now (and records it). */
    function resumeHintAllowed(now, maxPerWindow, windowMs) {
      if (!state) return false;
      var ha = state.hint_attempts || { count: 0, window_start: 0 };
      if (now - ha.window_start >= windowMs) {
        ha = { count: 0, window_start: now };
      }
      if (ha.count >= maxPerWindow) {
        state.hint_attempts = ha;
        persist();
        return false;
      }
      ha.count += 1;
      state.hint_attempts = ha;
      persist();
      return true;
    }

    function missingIndices() {
      if (!state) return null;
      var out = [];
      for (var i = 0; i < state.batch_count; i++) {
        if (!state.seen[i]) out.push(i);
      }
      return out;
    }

    /* Accept one restore envelope. Returns a Promise of a status object:
     *   {status:'legacy'}                 generation-less old envelope
     *   {status:'malformed'|'rejected'}   refused, nothing applied
     *   {status:'duplicate'}              batch already received
     *   {status:'accepted', received, total}
     *   {status:'complete', ops}          generation verified and done
     *   {status:'digest-mismatch'}        all batches but whole-gen digest
     *                                     failed: coverage reset, re-hint all
     * applyOps(ops) is called ONLY for newly accepted batches, in arrival
     * order (the caller serializes acceptEnvelope calls).
     */
    function acceptEnvelope(env, applyOps) {
      if (!env || typeof env !== 'object') {
        return Promise.resolve({ status: 'malformed' });
      }
      if (!isHex64(env.generation_id)) return Promise.resolve({ status: 'legacy' });
      if (!Number.isInteger(env.batch_index) || env.batch_index < 0 ||
          !Number.isInteger(env.batch_count) || env.batch_count < 1 ||
          env.batch_count > MAX_BATCH_COUNT ||
          env.batch_index >= env.batch_count ||
          !Array.isArray(env.ops)) {
        return Promise.resolve({ status: 'malformed' });
      }
      if (state && state.generation_id !== env.generation_id) {
        state = null; // newer generation supersedes stored coverage
      }
      if (!state) {
        state = {
          generation_id: env.generation_id,
          generation_digest: env.generation_digest,
          batch_count: env.batch_count,
          received: [], ops: [], seen: {},
        };
      }
      if (state.seen[env.batch_index]) {
        return Promise.resolve({ status: 'duplicate' });
      }
      return digest.verifyEnvelope(env).then(function (ok) {
        if (!ok) return { status: 'rejected' };
        state.seen[env.batch_index] = true;
        state.received.push(env.batch_index);
        var fresh = [];
        var have = {};
        state.ops.forEach(function (op) { have[opKey(op)] = true; });
        env.ops.forEach(function (op) {
          var k = opKey(op);
          if (!have[k]) { have[k] = true; state.ops.push(op); fresh.push(op); }
        });
        if (fresh.length && applyOps) applyOps(fresh);
        if (state.received.length < state.batch_count) {
          persist();
          return { status: 'accepted', received: state.received.length,
                   total: state.batch_count };
        }
        // completion gate: the whole accumulated op set must match the
        // generation digest (the same insertion-order-independent rule the
        // bot pinned in the frozen plan)
        return digest.canonicalDigest(state.ops).then(function (d) {
          if (d === state.generation_digest) {
            var n = state.ops.length;
            var doneGen = state.generation_id;
            state = null;
            persist();
            if (storage) {
              try {
                storage.setItem(COMPLETE_KEY,
                                JSON.stringify({ generation_id: doneGen }));
              } catch (e) { /* non-fatal: next open just re-hints plainly */ }
            }
            return { status: 'complete', ops: n };
          }
          // every batch verified but the whole does not: inconsistent
          // generation. Reset coverage so a re-hint refetches everything;
          // already-applied ops are idempotent under MergeDoc dedup.
          state = null;
          persist();
          return { status: 'digest-mismatch' };
        });
      });
    }

    function latestComplete() {
      if (!storage) return null;
      try {
        var d = JSON.parse(storage.getItem(COMPLETE_KEY) || 'null');
        return d && isHex64(d.generation_id) ? d.generation_id : null;
      } catch (e) { return null; }
    }

    load();
    return {
      acceptEnvelope: acceptEnvelope,
      latestComplete: latestComplete,
      missingIndices: missingIndices,
      resumeHintAllowed: resumeHintAllowed,
      currentGeneration: function () { return state && state.generation_id; },
      isIncomplete: function () { return !!state; },
      _key: STORAGE_KEY,
    };
  }

  root.ItdRestoreGen = { create: create, STORAGE_KEY: STORAGE_KEY,
                         COMPLETE_KEY: COMPLETE_KEY };
})(typeof window !== 'undefined' ? window : globalThis);
