// app.js - itdxdc shared-list webxdc prototype.
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
  // sheets the user opened stay open across re-renders (sync updates re-render)
  const openSheets = new Set();

  function persist() {
    try { localStorage.setItem(STORE_KEY, doc.serialize()); } catch (e) { /* best effort */ }
  }

  function sendOps(ops) {
    // one update may carry several ops; payload stays JSON-serializable
    window.webxdc.sendUpdate({ payload: { ops }, info: opsInfo(ops) }, '');
  }

  function opsInfo(ops) {
    // chat notice text; members-only audience, plain text (no markup)
    const op = ops[0];
    const title = doc.get(op.item, 'title');
    const name = title ? ': ' + title : '';
    if (ops.some(o => o.field.startsWith('_trim_'))) return 'Picked a version' + name;
    if (op.field === DELETED && op.value === 'true') return 'Removed item' + name;
    if (op.field === 'done' && op.value === 'true') return 'Marked done' + name;
    if (op.field === 'done' && op.value === 'false') return 'Marked not done' + name;
    if (op.field === 'title' && !title) return 'Added item: ' + op.value;
    if (op.field === 'title') return 'Renamed item to: ' + op.value;
    if (op.field === 'note') return 'Updated note' + name;
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
    // explicit user decision: re-assert the chosen value with a fresh stamp,
    // and record the decision itself as a normal field write so every device
    // converges on "conflicts older than this pick are resolved". The loser
    // stays in history (recoverable).
    const s = stamp();
    openSheets.delete(id); // decision made: collapse the sheet
    sendOps([
      { item: id, field, value, stamp: s },
      { item: id, field: '_trim_' + field, value, stamp: s },
    ]);
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
    ids.sort((a, b) => (((doc.getStamp(a, 'title') || {}).hlc || 0) - ((doc.getStamp(b, 'title') || {}).hlc || 0)));
    for (const id of ids) {
      const done = doc.get(id, 'done') === 'true';
      const title = doc.get(id, 'title') || '';
      const note = doc.get(id, 'note');
      const sheet = trimSheet(id, doc.conflicts(id));
      rows.push(
        '<div class="item' + (done ? ' done' : '') + '">' +
        '<button class="check" onclick="toggleDone(\'' + id + '\')">' + (done ? '&#9746;' : '&#9744;') + '</button>' +
        '<div class="body"><div class="title">' + esc(title) + '</div>' +
        (note ? '<div class="note">' + esc(note) + '</div>' : '') +
        (sheet.nUnresolved ? '<button class="badge" onclick="toggleTrim(\'' + id + '\')" aria-expanded="false">' + sheet.nUnresolved + ' version' + (sheet.nUnresolved > 1 ? 's' : '') + ' - pick one</button>' : '') +
        (!sheet.nUnresolved && sheet.nNoise ? '<button class="hist-toggle" onclick="toggleTrim(\'' + id + '\')">' + (openSheets.has(id) ? 'hide' : 'show') + ' ' + sheet.nNoise + ' earlier value' + (sheet.nNoise > 1 ? 's' : '') + '</button>' : '') +
        '</div>' +
        '<button class="del" onclick="removeItem(\'' + id + '\')">&times;</button>' +
        '</div>' +
        sheet.html
      );
    }
    list.innerHTML = rows.length ? rows.join('') : '<div class="empty">Nothing here yet. Add the first item below.</div>';
    document.getElementById('who').textContent = 'You: ' + shortDev(window.webxdc.selfAddr) + ' - updates sync to everyone in this chat';
  }

  // Split a field's trimmed losers into real choices and noise. A loser whose
  // value matches the current winner asks the user to "pick" what they already
  // have; repeated identical values are history, not a decision. Noise stays
  // recoverable (Keep buttons), just out of the way.
  function choices(id, field, losers) {
    const winner = String(doc.get(id, field));
    const trimStamp = doc.getStamp(id, '_trim_' + field);
    const sorted = [...losers].sort((a, b) => b.stamp.hlc - a.stamp.hlc);
    // a device whose latest word on this field already matches the winner
    // concurs; its older entries are its own edit trail, not a disagreement
    const devLatest = new Map();
    for (const l of sorted) {
      if (!devLatest.has(l.stamp.device)) devLatest.set(l.stamp.device, String(l.value));
    }
    const actionable = [], noise = [], seen = new Set();
    for (const l of sorted) {
      const v = String(l.value);
      // older than the user's explicit pick for this field: decided already
      if (trimStamp && l.stamp.hlc <= trimStamp.hlc) { noise.push(l); continue; }
      if (devLatest.get(l.stamp.device) === winner) { noise.push(l); continue; }
      if (v === winner || seen.has(v)) { noise.push(l); continue; }
      seen.add(v); actionable.push(l);
    }
    const noiseByVal = new Map();
    for (const n of noise) {
      const v = String(n.value);
      if (!noiseByVal.has(v)) noiseByVal.set(v, { value: n.value, stamp: n.stamp, count: 0 });
      noiseByVal.get(v).count++;
    }
    return { actionable, noise: [...noiseByVal.values()] };
  }

  function trimSheet(id, conflicts) {
    const byField = new Map();
    for (const c of conflicts) {
      if (!byField.has(c.field)) byField.set(c.field, []);
      byField.get(c.field).push(c);
    }
    let html = '';
    let nUnresolved = 0, nNoise = 0;
    const noiseRows = [];
    for (const [field, losers] of byField) {
      const ch = choices(id, field, losers);
      nNoise += ch.noise.reduce((n, e) => n + e.count, 0);
      if (!ch.actionable.length) { noiseRows.push(...ch.noise.map(e => ({ field, ...e }))); continue; }
      nUnresolved += ch.actionable.length;
      const wv = doc.get(id, field);
      const ws = doc.getStamp(id, field);
      html += '<div class="trim-field">Field: <b>' + esc(field) + '</b></div>';
      html += trimOption(id, field, wv, ws, true, 0);
      for (const l of ch.actionable) html += trimOption(id, field, l.value, l.stamp, false, 0);
      noiseRows.push(...ch.noise.map(e => ({ field, ...e })));
    }
    if (noiseRows.length) {
      html += '<div class="trim-hist">Earlier values - no decision needed, kept in history:</div>';
      for (const e of noiseRows) html += trimOption(id, e.field, e.value, e.stamp, false, e.count);
    }
    if (!html) return { html: '', nUnresolved: 0, nNoise: 0 };
    const cls = openSheets.has(id) ? 'trim' : 'trim hidden';
    return { html: '<div class="' + cls + '" id="trim-' + id + '">' + html + '</div>', nUnresolved, nNoise };
  }

  function trimOption(id, field, value, st, active, count) {
    return '<div class="branch ' + (active ? 'active' : 'trimmed') + '">' +
      '<div class="branch-val">' + esc(value) + (count > 1 ? ' <span class="dup">(x' + count + ')</span>' : '') + '</div>' +
      '<div class="branch-meta">' + esc(shortDev(st.device)) + ' - ' + fmtTime(st.hlc) + (active ? ' - active now' : ' - trimmed, kept in history') + '</div>' +
      '<div class="branch-actions">' +
      (active ? '' : '<button onclick="trimTo(\'' + id + '\',\'' + field + '\',' + JSON.stringify(String(value)).replace(/"/g, '&quot;') + ')">Keep this version</button>') +
      (field === 'title' && !active ? '<button onclick="keepBoth(\'' + id + '\',\'' + field + '\',' + JSON.stringify(String(value)).replace(/"/g, '&quot;') + ')">Keep both</button>' : '') +
      '</div></div>';
  }

  window.toggleTrim = function (id) {
    const el = document.getElementById('trim-' + id);
    if (!el) return;
    el.classList.toggle('hidden');
    if (el.classList.contains('hidden')) openSheets.delete(id); else openSheets.add(id);
    const btn = document.querySelector('button.badge[onclick="toggleTrim(\'' + id + '\')"]');
    if (btn) btn.setAttribute('aria-expanded', el.classList.contains('hidden') ? 'false' : 'true');
  };

  // ---------- boot ----------

  render();
  window.webxdc.setUpdateListener(applyUpdate, 0).then(render);
})();
