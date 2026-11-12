// mergedoc.js - JS port of the Android app's MergeDoc (com.instinct.widget.merge).
// Same semantics: per-field last-writer-wins registers ordered by (hlc, deviceId),
// lexical deviceId tie-break, LWW tombstone for deletes, losers kept in per-item
// history, heuristic conflict surface for the trim UI.
//
// Divergence from the JVM original is a bug: test/mergedoc.test.mjs runs the same
// property suite (convergence, idempotence, commutativity, associativity,
// tie-break, tombstone, conflict heuristic) against this port.
//
// No dependencies; runs in webxdc webviews and in Node for tests.

(function (global) {
  'use strict';

  const SKEW_WINDOW_MS = 10 * 60 * 1000;
  // trim decisions are recorded as ordinary field writes named _trim_<field>
  // so every device converges on which conflicts the user already resolved.
  const isTrimField = field => field.startsWith('_trim_') && FIELDS.has(field.slice(6));
  const FIELDS = new Set(['title', 'note', 'due', 'done']);
  const DELETED = 'deleted';

  function cmpStamp(a, b) {
    if (a.hlc !== b.hlc) return a.hlc < b.hlc ? -1 : 1;
    if (a.device === b.device) return 0;
    return a.device < b.device ? -1 : 1;
  }

  class MergeDoc {
    constructor() {
      this.items = new Map(); // id -> {fields: Map, deleted: {value, stamp}|null, history: Map(key->entry)}
    }

    apply(itemId, field, value, stamp) {
      if (!FIELDS.has(field) && field !== DELETED && !isTrimField(field)) throw new Error('unknown field: ' + field);
      let it = this.items.get(itemId);
      if (!it) {
        it = { fields: new Map(), deleted: null, history: new Map() };
        this.items.set(itemId, it);
      }
      const cur = field === DELETED ? it.deleted : it.fields.get(field);
      const hkey = field + '' + value + '' + stamp.hlc + '' + stamp.device;
      if (cur) {
        const c = cmpStamp(stamp, cur.stamp);
        if (c === 0) return; // exact duplicate
        if (c < 0) { it.history.set(hkey, { field, value, stamp }); return; } // late loser
        it.history.set(hkeyOf(field, cur), { field, value: cur.value, stamp: cur.stamp });
      }
      const next = { value, stamp };
      if (field === DELETED) it.deleted = next; else it.fields.set(field, next);
    }

    merge(other) {
      for (const [id, o] of other.items) {
        for (const [f, r] of o.fields) this.apply(id, f, r.value, r.stamp);
        if (o.deleted) this.apply(id, DELETED, o.deleted.value, o.deleted.stamp);
        for (const h of o.history.values()) this.apply(id, h.field, h.value, h.stamp);
      }
    }

    copy() {
      const d = new MergeDoc();
      d.merge(this);
      return d;
    }

    isVisible(itemId) {
      const it = this.items.get(itemId);
      if (!it) return false;
      return !it.deleted || it.deleted.value !== 'true';
    }

    get(itemId, field) {
      const it = this.items.get(itemId);
      if (!it) return null;
      const r = it.fields.get(field);
      return r ? r.value : null;
    }

    getStamp(itemId, field) {
      const it = this.items.get(itemId);
      if (!it) return null;
      const r = field === DELETED ? it.deleted : it.fields.get(field);
      return r ? r.stamp : null;
    }

    history(itemId) {
      const it = this.items.get(itemId);
      return it ? [...it.history.values()] : [];
    }

    // Heuristic: losers from a DIFFERENT device within the skew window of the
    // winner. HLC alone cannot prove causality; exact detection needs version
    // vectors (future work). Convergence stays automatic; the trim decision is
    // the user's.
    conflicts(itemId) {
      const it = this.items.get(itemId);
      if (!it) return [];
      const out = [];
      for (const h of it.history.values()) {
        const w = h.field === DELETED ? it.deleted : it.fields.get(h.field);
        if (!w) continue;
        if (w.stamp.device !== h.stamp.device &&
            Math.abs(w.stamp.hlc - h.stamp.hlc) <= SKEW_WINDOW_MS) out.push(h);
      }
      return out;
    }

    serialize() {
      const items = [];
      for (const [id, it] of this.items) {
        items.push({
          id,
          fields: [...it.fields.entries()].map(([f, r]) => [f, r.value, r.stamp.hlc, r.stamp.device]),
          deleted: it.deleted ? [it.deleted.value, it.deleted.stamp.hlc, it.deleted.stamp.device] : null,
          history: [...it.history.values()].map(h => [h.field, h.value, h.stamp.hlc, h.stamp.device]),
        });
      }
      return JSON.stringify({ v: 1, items });
    }

    static deserialize(json) {
      const d = new MergeDoc();
      const data = JSON.parse(json);
      for (const it of data.items || []) {
        for (const [f, value, hlc, device] of it.fields) d.apply(it.id, f, value, { hlc, device });
        if (it.deleted) d.apply(it.id, DELETED, it.deleted[0], { hlc: it.deleted[1], device: it.deleted[2] });
        for (const [f, value, hlc, device] of it.history) d.apply(it.id, f, value, { hlc, device });
      }
      return d;
    }

    equals(other) {
      if (this.items.size !== other.items.size) return false;
      for (const [id, it] of this.items) {
        const ot = other.items.get(id);
        if (!ot) return false;
        if (it.fields.size !== ot.fields.size) return false;
        for (const [f, r] of it.fields) {
          const or_ = ot.fields.get(f);
          if (!or_ || or_.value !== r.value || cmpStamp(or_.stamp, r.stamp) !== 0) return false;
        }
        if (!!it.deleted !== !!ot.deleted) return false;
        if (it.deleted && (it.deleted.value !== ot.deleted.value || cmpStamp(it.deleted.stamp, ot.deleted.stamp) !== 0)) return false;
        if (it.history.size !== ot.history.size) return false;
        for (const k of it.history.keys()) if (!ot.history.has(k)) return false;
      }
      return true;
    }
  }

  function hkeyOf(field, reg) {
    return field + '' + reg.value + '' + reg.stamp.hlc + '' + reg.stamp.device;
  }

  global.MergeDoc = MergeDoc;
  global.MERGE_FIELDS = FIELDS;
  global.MERGE_DELETED = DELETED;
  global.MERGE_SKEW_WINDOW_MS = SKEW_WINDOW_MS;
})(typeof window !== 'undefined' ? window : globalThis);
