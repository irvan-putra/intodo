// app.js - Instinct Shared List webxdc prototype.
// PROTOTYPE with invented sample data. Profile/membership and multi-network
// layers are NOT built - this proves shared-list updates, merge convergence and
// conflict trimming inside one chat. The native Android widget stays separate.
//
// Every user action becomes ops on the MergeDoc; ops are sent as webxdc updates
// and applied from the update listener (own updates included, per the spec), so
// every member of the chat converges on the same list.

(function () {
  'use strict';

  const MergeDoc = window.MergeDoc;
  const DELETED = window.MERGE_DELETED;
  const STORE_KEY = 'instinct-shared-list-doc-v1';

  let lastHlc = 0;
  function stamp() {
    // hybrid logical clock: physical time, bumped past anything we have seen
    lastHlc = Math.max(Date.now(), lastHlc + 1);
    return { hlc: lastHlc, device: window.webxdc.selfAddr };
  }

  function loadDoc() {
    try {
      const raw = localStorage.getItem(STORE_KEY);
      if (raw) return MergeDoc.deserialize(raw);
    } catch (e) { /* corrupted store: start clean */ }
    const d = new MergeDoc();
    seed(d);
    return d;
  }

  function seed(d) {
    // invented, non-sensitive sample items
    const s = { hlc: Date.now() - 60000, device: 'sample' };
    const items = [
      ['sample-1', 'Buy oat milk', ''],
      ['sample-2', 'Pay electricity bill', 'Due Friday'],
      ['sample-3', 'Call Mom', ''],
    ];
    for (const [id, title, note] of items) {
      d.apply(id, 'title', title, s);
      if (note) d.apply(id, 'note', note, s);
      d.apply(id, 'done', 'false', s);
    }
  }

  let doc = loadDoc();

  function persist() {
    try { localStorage.setItem(STORE_KEY, doc.serialize()); } catch (e) { /* best effort */ }
  }

  function sendOps(ops) {
    // one update may carry several ops; payload stays JSON-serializable
    window.webxdc.sendUpdate({ payload: { ops }, info: opsInfo(ops) }, '');
  }

  function opsInfo(ops) {
    const op = ops[0];
    if (op.field === 'done' && op.value === 'true') return 'Checked off an item';
    if (op.field === DELETED && op.value === 'true') return 'Removed an item';
    if (op.field === 'title' && !doc.get(op.item, 'title')) return 'Added an item';
    return 'Updated the list';
  }

  function applyUpdate(update) {
    const ops = update.payload && update.payload.ops;
    if (!ops) return;
    for (const op of ops) {
      doc.apply(op.item, op.field, op.value, op.stamp);
      if (op.stamp.hlc > lastHlc) lastHlc = op.stamp.hlc;
    }
    persist();
    render();
  }

  // ---------- actions ----------

  window.addItem = function () {
    const input = document.getElementById('newTitle');
    const title = input.value.trim();
    if (!title) return;
    const id = 'item-' + Date.now().toString(36) + '-' + Math.floor(Math.random() * 1e6).toString(36);
    const s = stamp();
    sendOps([
      { item: id, field: 'title', value: title, stamp: s },
      { item: id, field: 'done', value: 'false', stamp: s },
    ]);
    input.value = '';
  };

  window.toggleDone = function (id) {
    sendOps([{ item: id, field: 'done', value: doc.get(id, 'done') === 'true' ? 'false' : 'true', stamp: stamp() }]);
  };

  window.removeItem = function (id) {
    sendOps([{ item: id, field: DELETED, value: 'true', stamp: stamp() }]);
  };

  window.trimTo = function (id, field, value) {
    // explicit user decision: re-assert the chosen value with a fresh stamp;
    // the loser stays in history (recoverable)
    sendOps([{ item: id, field, value, stamp: stamp() }]);
    render();
  };

  window.keepBoth = function (id, field, value) {
    const copyId = 'item-' + Date.now().toString(36) + '-' + Math.floor(Math.random() * 1e6).toString(36);
    const s = stamp();
    sendOps([
      { item: copyId, field: 'title', value, stamp: s },
      { item: copyId, field: 'done', value: 'false', stamp: s },
    ]);
  };

  // ---------- rendering ----------

  function esc(s) {
    return String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  }

  function fmtTime(hlc) {
    return new Date(hlc).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
  }

  function shortDev(device) {
    return device.length > 14 ? device.slice(0, 6) + '...' + device.slice(-4) : device;
  }

  function render() {
    const list = document.getElementById('list');
    const rows = [];
    const ids = [...doc.items.keys()].filter(id => doc.isVisible(id));
    ids.sort((a, b) => (doc.getStamp(a, 'title').hlc - doc.getStamp(b, 'title').hlc));
    for (const id of ids) {
      const done = doc.get(id, 'done') === 'true';
      const title = doc.get(id, 'title') || '';
      const note = doc.get(id, 'note');
      const conflicts = doc.conflicts(id);
      const nFields = new Set(conflicts.map(c => c.field)).size;
      rows.push(
        '<div class="item' + (done ? ' done' : '') + '">' +
        '<button class="check" onclick="toggleDone(\'' + id + '\')">' + (done ? '&#9746;' : '&#9744;') + '</button>' +
        '<div class="body"><div class="title">' + esc(title) + '</div>' +
        (note ? '<div class="note">' + esc(note) + '</div>' : '') +
        (nFields ? '<button class="badge" onclick="showTrim(\'' + id + '\')">' + nFields + ' version' + (nFields > 1 ? 's' : '') + ' - pick one</button>' : '') +
        '</div>' +
        '<button class="del" onclick="removeItem(\'' + id + '\')">&times;</button>' +
        '</div>' +
        (conflicts.length ? trimSheet(id, conflicts) : '')
      );
    }
    list.innerHTML = rows.length ? rows.join('') : '<div class="empty">Nothing here yet. Add the first item below.</div>';
    document.getElementById('who').textContent = 'You: ' + shortDev(window.webxdc.selfAddr) + ' - updates sync to everyone in this chat';
  }

  function trimSheet(id, conflicts) {
    const byField = new Map();
    for (const c of conflicts) {
      if (!byField.has(c.field)) byField.set(c.field, []);
      byField.get(c.field).push(c);
    }
    let html = '<div class="trim">';
    for (const [field, losers] of byField) {
      const wv = doc.get(id, field);
      const ws = doc.getStamp(id, field);
      html += '<div class="trim-field">Field: <b>' + esc(field) + '</b></div>';
      html += trimOption(id, field, wv, ws, true);
      for (const l of losers) html += trimOption(id, field, l.value, l.stamp, false);
    }
    html += '</div>';
    return html;
  }

  function trimOption(id, field, value, st, active) {
    return '<div class="branch ' + (active ? 'active' : 'trimmed') + '">' +
      '<div class="branch-val">' + esc(value) + '</div>' +
      '<div class="branch-meta">' + esc(shortDev(st.device)) + ' - ' + fmtTime(st.hlc) + (active ? ' - active now' : ' - trimmed, kept in history') + '</div>' +
      '<div class="branch-actions">' +
      (active ? '' : '<button onclick="trimTo(\'' + id + '\',\'' + field + '\',' + JSON.stringify(String(value)).replace(/"/g, '&quot;') + ')">Keep this version</button>') +
      (field === 'title' && !active ? '<button onclick="keepBoth(\'' + id + '\',\'' + field + '\',' + JSON.stringify(String(value)).replace(/"/g, '&quot;') + ')">Keep both</button>' : '') +
      '</div></div>';
  }

  window.showTrim = function () { /* sheet renders inline; nothing extra needed */ };

  // ---------- boot ----------

  render();
  window.webxdc.setUpdateListener(applyUpdate, 0).then(render);
})();
