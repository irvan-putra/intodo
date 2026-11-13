// seedfix.js - deterministic sample seed + repair for clock-stamped seeds.
// Environment-agnostic (webxdc webviews and Node tests), same pattern as
// mergedoc.js. Prototype sample items are LOCAL-ONLY: they are applied to the
// MergeDoc on first open and never sent as updates. They used to be stamped
// with the OPENING client's wall clock (Date.now()-60000), so a client whose
// first open happened after real edits seeded stamps NEWER than those edits;
// per-field LWW then let the seeds win: divergent done states and item order
// across clients, with no conflict badge (outside the 10-minute skew window).
// Fix: seeds use a fixed ancient stamp (hlc 0). Real ops always carry
// Date.now()-based stamps (> 0), so they always outrank seeds on every
// client, regardless of when each client first opened the app.

(function (global) {
  'use strict';

  const DELETED = global.MERGE_DELETED || 'deleted';
  const SEED_HLC = 0;
  const SEED_DEVICE = 'sample';
  const SEED_STAMP = { hlc: SEED_HLC, device: SEED_DEVICE };
  const SAMPLE_ITEMS = [
    ['sample-1', 'Buy oat milk', ''],
    ['sample-2', 'Pay electricity bill', 'Due Friday'],
    ['sample-3', 'Call Mom', ''],
  ];

  function seedDoc(d) {
    // invented, non-sensitive sample items
    for (const [id, title, note] of SAMPLE_ITEMS) {
      d.apply(id, 'title', title, SEED_STAMP);
      if (note) d.apply(id, 'note', note, SEED_STAMP);
      d.apply(id, 'done', 'false', SEED_STAMP);
    }
  }

  function isSeedStamp(s) {
    return !!s && s.device === SEED_DEVICE && s.hlc !== SEED_HLC;
  }
  function fixStamp(s) {
    return isSeedStamp(s) ? { hlc: SEED_HLC, device: SEED_DEVICE } : s;
  }

  // Repair an already-persisted doc whose seeds were clock-stamped: re-run
  // the merge over EVERY entry (current winners, history losers, tombstones)
  // with seed stamps reset to the fixed ancient stamp. LWW then promotes any
  // real op a poisoned seed had displaced, and demoted seeds stay in history
  // (nothing is dropped, nothing is sent, no notices). Real-device entries
  // keep their exact stamps. Deterministic: two clients holding the same
  // logical entries repair to equal docs. Idempotent.
  function repairSeedStamps(MergeDoc, doc) {
    let changed = false;
    const fixed = new MergeDoc();
    for (const [id, it] of doc.items) {
      for (const [f, r] of it.fields) {
        if (isSeedStamp(r.stamp)) changed = true;
        fixed.apply(id, f, r.value, fixStamp(r.stamp));
      }
      if (it.deleted) {
        if (isSeedStamp(it.deleted.stamp)) changed = true;
        fixed.apply(id, DELETED, it.deleted.value, fixStamp(it.deleted.stamp));
      }
      for (const h of it.history.values()) {
        if (isSeedStamp(h.stamp)) changed = true;
        fixed.apply(id, h.field, h.value, fixStamp(h.stamp));
      }
    }
    return { doc: changed ? fixed : doc, repaired: changed };
  }

  global.SEEDFIX = { SEED_STAMP: SEED_STAMP, seedDoc: seedDoc, repairSeedStamps: repairSeedStamps };
})(typeof window !== 'undefined' ? window : globalThis);
