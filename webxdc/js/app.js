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
  const BIND_KEY = 'instinct-shared-list-binding-v1';
  const APP_VERSION = '0.4.7'; // keep in step with manifest.toml version
  // List binding: an instance only accepts restore envelopes for its bound
  // list_id. Binding comes from trusted packaging (list.json packed by the
  // bot at publish) or an explicit in-app owner confirmation - NEVER from
  // silently adopting the first author-blind envelope (that would be TOFU).
  let boundListId = null;   // string once bound
  let bindSource = null;    // 'packaged' | 'owner'
  let pendingBindId = null; // offered by an envelope, awaiting owner tap
  let bootHintSent = false;

  // Local diagnostics: what happened to restore envelopes, on this device
  // only. Events are facts ("accepted 3 ops", "rejected: digest mismatch"),
  // never completion claims, and never leave the device (no info/notice).
  const DIAG_KEY = 'instinct-shared-list-diag-v1';
  let diagLog = [];
  try { diagLog = JSON.parse(localStorage.getItem(DIAG_KEY) || '[]'); } catch (e) {}
  function diag(msg) {
    diagLog.push({ ts: Date.now(), msg });
    if (diagLog.length > 20) diagLog = diagLog.slice(-20);
    try { localStorage.setItem(DIAG_KEY, JSON.stringify(diagLog)); } catch (e) {}
    renderDiag();
  }
  function renderDiag() {
    const el = document.getElementById('diag');
    if (!el) return;
    if (!diagLog.length) { el.style.display = 'none'; return; }
    el.style.display = '';
    const last = diagLog[diagLog.length - 1];
    el.innerHTML = '<span class="diag-line"></span>';
    el.querySelector('.diag-line').textContent = 'restore: ' + last.msg;
  }

  function loadBinding() {
    try {
      const raw = localStorage.getItem(BIND_KEY);
      if (!raw) return;
      const b = JSON.parse(raw);
      if (b && typeof b.listId === 'string' && b.listId) {
        boundListId = b.listId; bindSource = b.source === 'packaged' ? 'packaged' : 'owner';
      }
    } catch (e) { /* ignore corrupted binding */ }
  }
  function saveBinding() {
    try { localStorage.setItem(BIND_KEY, JSON.stringify({ listId: boundListId, source: bindSource })); } catch (e) {}
  }
  // Packaged binding: the bot can pack list.json {list_id} into the .xdc at
  // publish time; that is trusted packaging, not a wire claim.
  function loadPackagedBinding() {
    if (boundListId) return Promise.resolve();
    return fetch('list.json').then(r => r.ok ? r.json() : null).then(j => {
      if (j && typeof j.list_id === 'string' && j.list_id) {
        boundListId = j.list_id; bindSource = 'packaged'; saveBinding();
      }
    }).catch(() => {});
  }

  let lastHlc = 0;
  function stamp() {
    // hybrid logical clock: physical time, bumped past anything we have seen
    lastHlc = Math.max(Date.now(), lastHlc + 1);
    return { hlc: lastHlc, device: window.webxdc.selfAddr };
  }

  function loadDoc() {
    try {
      const raw = localStorage.getItem(STORE_KEY);
      if (raw) {
        // repair docs persisted before seed stamps were fixed (seedfix.js):
        // re-runs the merge so displaced real edits win again; local only
        const r = window.SEEDFIX.repairSeedStamps(MergeDoc, MergeDoc.deserialize(raw));
        return r.doc;
      }
    } catch (e) { /* corrupted store: start clean */ }
    const d = new MergeDoc();
    seed(d);
    return d;
  }

  function seed(d) {
    // invented, non-sensitive sample items - fixed ancient stamp (seedfix.js)
    window.SEEDFIX.seedDoc(d);
  }

  let doc = loadDoc();
  // sheets the user opened stay open across re-renders (sync updates re-render)
  const openSheets = new Set();

  function persist() {
    try { localStorage.setItem(STORE_KEY, doc.serialize()); } catch (e) { /* best effort */ }
  }

  function sendOps(ops) {
    // one update may carry several ops; payload stays JSON-serializable
    // href: keeps every notice as its own message (core only collapses
    // consecutive infos when href is absent) and makes tapping one open
    // the app. The file part is REQUIRED: a pure-fragment href makes the
    // messenger's webview request "/" (not a zip entry) and show
    // "Webxdc Request Error: \"/\" not found" instead of the app.
    window.webxdc.sendUpdate({ payload: { ops }, info: opsInfo(ops), href: 'index.html#a' }, '');
  }

  function opsInfo(ops) {
    // chat notice text; members-only audience, plain text (no markup)
    const op = ops[0];
    const title = doc.get(op.item, 'title');
    const name = title ? ': ' + title : '';
    if (ops.some(o => o.field.startsWith('_trim_'))) return 'Picked a version' + name;
    if (op.field === DELETED && op.value === 'true') return 'Removed item' + name;
    if (op.field === DELETED && op.value === 'false') return 'Restored item' + name;
    if (op.field === 'done' && op.value === 'true') return 'Marked done' + name;
    if (op.field === 'done' && op.value === 'false') return 'Marked not done' + name;
    if (op.field === 'title' && !title) return 'Added item: ' + op.value;
    if (op.field === 'title') return 'Renamed item to: ' + op.value;
    if (op.field === 'note') return 'Updated note' + name;
    return 'Updated the list';
  }

  function applyUpdate(update) {
    const p = update.payload;
    if (p && p.itd_restore_v1 === true) { queueRestoreEnvelope(p); return; }
    if (p && typeof p.list_id === 'string' && typeof p.boot !== 'undefined' && !p.ops) return; // boot hint: not ops
    const ops = p && p.ops;
    if (!ops) return;
    for (const op of ops) {
      doc.apply(op.item, op.field, op.value, op.stamp);
      if (op.stamp.hlc > lastHlc) lastHlc = op.stamp.hlc;
    }
    persist();
    render();
  }

  // ---------- restore generations (itd_restore_v2) ----------
  // Serialized per-generation tracking: batch dedup by (generation, index),
  // op dedup by identity, completion only when every batch arrived AND the
  // whole accumulated op set matches the generation digest. Coverage of the
  // latest incomplete generation persists across app restarts.
  const restoreTracker = window.ItdRestoreGen.create({});
  let restoreQueue = Promise.resolve();
  let resumeHintLast = 0;

  function queueRestoreEnvelope(env) {
    restoreQueue = restoreQueue.then(() => handleRestoreEnvelope(env));
  }

  function handleRestoreEnvelope(env) {
    if (boundListId && env.list_id !== boundListId) {
      diag('ignored batch for a different list');
      return Promise.resolve();
    }
    if (!boundListId) return applyRestoreEnvelope(env); // unbound: owner-link flow
    return restoreTracker.acceptEnvelope(env, applyOps).then(st => {
      switch (st.status) {
        case 'legacy': return applyRestoreEnvelope(env);
        case 'malformed': diag('ignored malformed batch'); break;
        case 'rejected': diag('rejected batch (digest or op domain)'); break;
        case 'duplicate': break; // idempotent redelivery: nothing to do
        case 'accepted':
          diag('received ' + st.received + '/' + st.total + ' batches');
          break;
        case 'complete':
          diag('complete (' + st.ops + ' ops)');
          break;
        case 'digest-mismatch':
          diag('restore inconsistent - requesting it again');
          break;
      }
      render();
    });
  }

  function applyOps(ops) {
    for (const op of ops) {
      doc.apply(op.item, op.field, op.value, op.stamp);
      if (op.stamp.hlc > lastHlc) lastHlc = op.stamp.hlc;
    }
    persist();
  }

  // While a generation is incomplete, ask the bot for exactly the missing
  // batches: once per open, then at most every 300s while open. The bot
  // enforces its own per-generation resume budget on top of this.
  function maybeSendResumeHint() {
    if (!boundListId || !restoreTracker.isIncomplete()) return;
    const now = Date.now();
    if (now - resumeHintLast < 300000) return;
    // persisted per-generation budget (3 per 30 min, same bound as the bot):
    // an app restart cannot reset it, an exhausted window stays silent
    if (!restoreTracker.resumeHintAllowed(now, 3, 1800000)) return;
    resumeHintLast = now;
    try {
      window.webxdc.sendUpdate({ payload: {
        list_id: boundListId,
        boot: APP_VERSION,
        resume: restoreTracker.currentGeneration(),
        missing: restoreTracker.missingIndices() || [],
      } }, '');
    } catch (e) { resumeHintLast = 0; }
  }

  function applyRestoreEnvelope(env) {
    if (!Array.isArray(env.ops)) { diag('ignored malformed batch'); return; }
    // Cross-list guard: ops carry no list_id, so an envelope for another
    // list would silently corrupt this doc. Refuse mismatches outright.
    if (boundListId && env.list_id !== boundListId) { diag('ignored batch for a different list'); return; }
    if (!boundListId) {
      // Unbound instance: offer explicit owner binding, adopt nothing.
      if (env.list_id && pendingBindId !== env.list_id) {
        pendingBindId = env.list_id;
        diag('batch offered for list ' + env.list_id + ' - awaiting link');
        render();
      }
      return;
    }
    // Corruption-integrity only; never authenticates the bot. Fails closed.
    window.ItdDigest.verifyEnvelope(env).then(ok => {
      if (!ok) { diag('rejected batch (digest or op domain)'); return; }
      for (const op of env.ops) {
        doc.apply(op.item, op.field, op.value, op.stamp);
        if (op.stamp.hlc > lastHlc) lastHlc = op.stamp.hlc;
      }
      persist();
      diag('accepted ' + env.ops.length + ' op(s)');
      render();
    });
  }

  window.confirmBindList = function () {
    if (!pendingBindId) return;
    boundListId = pendingBindId; bindSource = 'owner'; pendingBindId = null;
    saveBinding();
    render();
  };

  function maybeSendBootHint(isRetry) {
    if (bootHintSent || !boundListId) return;
    bootHintSent = true;
    try {
      const hint = { list_id: boundListId, boot: APP_VERSION };
      // verified-complete claim: lets the bot no-op healthy opens with no
      // recovery-budget burn; omitted after total loss (plain hint then
      // requeues the full generation, bounded by the shared budget)
      const complete = restoreTracker.latestComplete();
      if (complete) hint.complete = complete;
      window.webxdc.sendUpdate({ payload: hint }, '');
      window.__lastBootHint = hint; // test hook
    } catch (e) {
      bootHintSent = false;
      // transient transport race (dev simulator websocket still connecting;
      // the real core queues sends) - retry once, then leave it to next open
      if (!isRetry) setTimeout(() => maybeSendBootHint(true), 2000);
    }
  }

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
    const _cv = doc.get(id, 'done'); sendOps([{ item: id, field: 'done', value: (_cv === 'true' || _cv === true) ? 'false' : 'true', stamp: stamp() }]);
  };

  window.removeItem = function (id) {
    sendOps([{ item: id, field: DELETED, value: 'true', stamp: stamp() }]);
  };

  // archive: removed items keep all data (LWW tombstone, fields and value
  // history stay in the doc) and can be restored with a newer deleted:false
  window.restoreItem = function (id) {
    sendOps([{ item: id, field: DELETED, value: 'false', stamp: stamp() }]);
  };

  let archiveOpen = false;
  window.toggleArchive = function () {
    archiveOpen = !archiveOpen;
    render();
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
    const bn = document.getElementById('bindNotice');
    if (bn) {
      if (pendingBindId) {
        bn.style.display = '';
        bn.innerHTML = '<span>A restore was offered for list <b></b>. Link this app to that list? </span>' +
          '<button style="background:#F59E0B;color:#101828;border:0;border-radius:4px;padding:4px 10px" onclick="confirmBindList()">Link</button>';
        bn.querySelector('b').textContent = pendingBindId;
      } else { bn.style.display = 'none'; bn.innerHTML = ''; }
    }
    const list = document.getElementById('list');
    const rows = [];
    const ids = [...doc.items.keys()].filter(id => doc.isVisible(id));
    // primary: title-write stamp (creation order, unchanged semantics);
    // tie-break: item id, so equal stamps (the fixed hlc-0 seeds) order
    // deterministically on every client instead of by Map insertion order
    ids.sort((a, b) => (((doc.getStamp(a, 'title') || {}).hlc || 0) - ((doc.getStamp(b, 'title') || {}).hlc || 0))
      || (a < b ? -1 : a > b ? 1 : 0));
    for (const id of ids) {
      const _dv = doc.get(id, 'done'); const done = _dv === 'true' || _dv === true;
      const _tv = doc.get(id, 'title'); const title = (_tv === null || _tv === undefined) ? '' : String(_tv);
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

    // archived = tombstoned items; data is kept, restore is one tap
    const arch = document.getElementById('archived');
    const archived = [...doc.items.keys()].filter(id => !doc.isVisible(id));
    archived.sort((a, b) => (((doc.getStamp(b, DELETED) || {}).hlc || 0) - ((doc.getStamp(a, DELETED) || {}).hlc || 0)));
    if (!archived.length) {
      arch.innerHTML = '';
    } else {
      const parts = ['<button class="arch-toggle" onclick="toggleArchive()">' +
        (archiveOpen ? 'hide' : 'show') + ' archived (' + archived.length + ')</button>'];
      if (archiveOpen) {
        for (const id of archived) {
          const title = doc.get(id, 'title') || '(untitled)';
          const ds = doc.getStamp(id, DELETED);
          parts.push(
            '<div class="item arch">' +
            '<div class="body"><div class="title">' + esc(title) + '</div>' +
            (ds ? '<div class="note">removed ' + esc(fmtTime(ds.hlc)) + '</div>' : '') +
            '</div>' +
            '<button class="restore" onclick="restoreItem(\'' + id + '\')">restore</button>' +
            '</div>'
          );
        }
      }
      arch.innerHTML = parts.join('');
    }
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
    if (noiseRows.length && !nUnresolved) {
      // noise-only sheet: the outer "show earlier values" button reveals it directly
      html += '<div class="trim-hist">Earlier values - no decision needed, kept in history:</div>';
      for (const e of noiseRows) html += trimOption(id, e.field, e.value, e.stamp, false, e.count);
    } else if (noiseRows.length) {
      // decision sheet: history stays collapsed behind its own nested toggle
      const hOpen = openSheets.has(id + '|h');
      html += '<button class="hist-toggle" onclick="toggleHist(\'' + id + '\')">' + (hOpen ? 'hide' : 'show') + ' ' + noiseRows.reduce((n, e) => n + (e.count || 1), 0) + ' earlier value' + (noiseRows.reduce((n, e) => n + (e.count || 1), 0) > 1 ? 's' : '') + '</button>' +
        '<div id="hist-' + id + '"' + (hOpen ? '' : ' class="hidden"') + '><div class="trim-hist">Earlier values - no decision needed, kept in history:</div>';
      for (const e of noiseRows) html += trimOption(id, e.field, e.value, e.stamp, false, e.count);
      html += '</div>';
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

  window.toggleHist = function (id) {
    const el = document.getElementById('hist-' + id);
    if (!el) return;
    el.classList.toggle('hidden');
    const nowHidden = el.classList.contains('hidden');
    if (nowHidden) openSheets.delete(id + '|h'); else openSheets.add(id + '|h');
    const hBtn = document.querySelector('button.hist-toggle[onclick="toggleHist(\'' + id + '\')"]');
    if (hBtn) hBtn.textContent = hBtn.textContent.replace(/^(show|hide)/, nowHidden ? 'show' : 'hide');
  };

  window.toggleTrim = function (id) {
    const el = document.getElementById('trim-' + id);
    if (!el) return;
    el.classList.toggle('hidden');
    if (el.classList.contains('hidden')) openSheets.delete(id); else openSheets.add(id);
    const hidden = el.classList.contains('hidden');
    const btn = document.querySelector('button.badge[onclick="toggleTrim(\'' + id + '\')"]');
    if (btn) btn.setAttribute('aria-expanded', hidden ? 'false' : 'true');
    const hBtn = document.querySelector('button.hist-toggle[onclick="toggleTrim(\'' + id + '\')"]');
    if (hBtn) hBtn.textContent = hBtn.textContent.replace(/^(show|hide)/, hidden ? 'show' : 'hide');
  };

  // ---------- boot ----------

  loadBinding();
  render();
  renderDiag();
  loadPackagedBinding().then(() => {
    render();
    maybeSendBootHint();
    maybeSendResumeHint();
    setInterval(maybeSendResumeHint, 300000);
    return window.webxdc.setUpdateListener(applyUpdate, 0);
  }).then(render);
})();
