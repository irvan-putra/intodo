import React, { useMemo, useState } from 'react';
import { MergeDoc, MERGE_DELETED } from './engine';
import xdcZip from './itdxdc-v0.4.zip';

// Demo transport: simulates what Delta Chat does for a real webxdc mini app -
// every update one phone sends is delivered to every phone in the chat.
class Bus {
  listeners: ((u: any) => void)[] = [];
  log: any[] = [];
  send(u: any) { this.log.push(u); this.listeners.forEach(cb => cb(u)); }
  subscribe(cb: (u: any) => void) { this.listeners.push(cb); this.log.forEach(cb); }
}

// One simulated phone: its own MergeDoc, HLC clock and address - mirroring
// js/app.js in the .xdc (same engine, same op flow).
class Phone {
  doc = new MergeDoc();
  lastHlc = 0;
  constructor(public addr: string, bus: Bus, private onChange: () => void) {
    bus.subscribe(u => this.applyUpdate(u));
    this.seed();
  }
  stamp() {
    this.lastHlc = Math.max(Date.now(), this.lastHlc + 1);
    return { hlc: this.lastHlc, device: this.addr };
  }
  seed() {
    const s = { hlc: Date.now() - 60000, device: 'sample' };
    const items: [string, string, string][] = [
      ['sample-1', 'Buy oat milk', ''],
      ['sample-2', 'Pay electricity bill', 'Due Friday'],
      ['sample-3', 'Call Mom', ''],
    ];
    for (const [id, title, note] of items) {
      this.doc.apply(id, 'title', title, s);
      if (note) this.doc.apply(id, 'note', note, s);
      this.doc.apply(id, 'done', 'false', s);
    }
  }
  applyUpdate(u: any) {
    for (const op of u.payload?.ops || []) {
      this.doc.apply(op.item, op.field, op.value, op.stamp);
      if (op.stamp.hlc > this.lastHlc) this.lastHlc = op.stamp.hlc;
    }
    this.onChange();
  }
}

function fmtTime(hlc: number) {
  return new Date(hlc).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}
function shortDev(d: string) { return d.length > 16 ? d.slice(0, 8) + '...' + d.slice(-4) : d; }

const css: Record<string, React.CSSProperties> = {
  phone: { width: '100%', maxWidth: 380, boxSizing: 'border-box', background: '#101828', borderRadius: 12, border: '1px solid #344054', overflow: 'hidden', fontFamily: 'system-ui, sans-serif' },
  head: { padding: '12px 14px 6px', fontSize: 16, fontWeight: 700, color: '#fff' },
  sub: { padding: '0 14px 8px', fontSize: 11, color: '#98A2B3' },
  item: { display: 'flex', alignItems: 'flex-start', gap: 8, padding: '10px 12px', borderTop: '1px solid #1D2939', flexWrap: 'wrap' as const },
  check: { background: 'none', border: 'none', color: '#F59E0B', fontSize: 18, cursor: 'pointer', padding: '0 2px' },
  del: { background: 'none', border: 'none', color: '#475467', fontSize: 16, cursor: 'pointer' },
  badge: { marginTop: 6, background: '#3B2F0B', color: '#F59E0B', border: '1px solid #F59E0B', borderRadius: 999, fontSize: 11, padding: '3px 10px', cursor: 'pointer' },
  trim: { flexBasis: '100%', margin: '6px 0 2px', padding: 8, background: '#1D2939', borderRadius: 10 },
  branch: (active: boolean) => ({ borderLeft: `3px ${active ? 'solid' : 'dashed'} ${active ? '#12B76A' : '#7A5AF8'}`, padding: '6px 8px', margin: '6px 0' }),
  btn: { background: '#175CD3', color: '#fff', border: 'none', borderRadius: 6, fontSize: 12, padding: '5px 10px', marginRight: 6, cursor: 'pointer' },
  btnAlt: { background: '#1D2939', color: '#fff', border: '1px solid #475467', borderRadius: 6, fontSize: 12, padding: '5px 10px', cursor: 'pointer' },
};

function PhoneView({ phone, bus, color }: { phone: Phone; bus: Bus; color: string }) {
  const [, force] = useState(0);
  const [newTitle, setNewTitle] = useState('');
  const [openTrim, setOpenTrim] = useState<string | null>(null);
  phone.onChange = () => force(x => x + 1);

  const send = (ops: any[]) => bus.send({ payload: { ops } });

  const addItem = () => {
    const title = newTitle.trim();
    if (!title) return;
    const id = 'item-' + Date.now().toString(36) + '-' + Math.floor(Math.random() * 1e6).toString(36);
    const s = phone.stamp();
    send([
      { item: id, field: 'title', value: title, stamp: s },
      { item: id, field: 'done', value: 'false', stamp: s },
    ]);
    setNewTitle('');
  };

  const ids = [...phone.doc.items.keys()].filter((id: string) => phone.doc.isVisible(id))
    .sort((a: string, b: string) => phone.doc.getStamp(a, 'title').hlc - phone.doc.getStamp(b, 'title').hlc);

  return (
    <div>
      <h2 style={{ fontSize: 14, color }}>{phone.addr}</h2>
      <div data-xdev={phone.addr.includes(" A ") ? "A" : "B"} style={css.phone}>
        <div style={css.head}>itdxdc</div>
        <div style={css.sub}>local-first prototype - merges offline edits, you pick what survives a conflict</div>
        {ids.length === 0 && <div style={{ color: '#98A2B3', fontSize: 13, padding: 16, textAlign: 'center' }}>Nothing here yet.</div>}
        {ids.map((id: string) => {
          const done = phone.doc.get(id, 'done') === 'true';
          const note = phone.doc.get(id, 'note');
          const conflicts = phone.doc.conflicts(id);
          const nFields = new Set(conflicts.map((c: any) => c.field)).size;
          return (
            <div key={id} style={css.item}>
              <button style={css.check} onClick={() => send([{ item: id, field: 'done', value: done ? 'false' : 'true', stamp: phone.stamp() }])}>
                {done ? '☑' : '☐'}
              </button>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontSize: 14, color: done ? '#667085' : '#fff', textDecoration: done ? 'line-through' : 'none' }}>{phone.doc.get(id, 'title')}</div>
                {note && <div style={{ color: '#98A2B3', fontSize: 12, marginTop: 2 }}>{note}</div>}
                {nFields > 0 && (
                  <button style={css.badge} onClick={() => setOpenTrim(openTrim === id ? null : id)}>
                    {nFields} version{nFields > 1 ? 's' : ''} - pick one
                  </button>
                )}
              </div>
              <button style={css.del} onClick={() => send([{ item: id, field: MERGE_DELETED, value: 'true', stamp: phone.stamp() }])}>×</button>
              {openTrim === id && conflicts.length > 0 && (
                <div style={css.trim}>
                  {[...new Set(conflicts.map((c: any) => c.field))].map(field => {
                    const wv = phone.doc.get(id, field as string);
                    const ws = phone.doc.getStamp(id, field as string);
                    const losers = conflicts.filter((c: any) => c.field === field);
                    const opt = (value: string, st: any, active: boolean, k: string) => (
                      <div key={k} style={css.branch(active)}>
                        <div style={{ fontSize: 14, color: '#fff' }}>{value}</div>
                        <div style={{ color: '#667085', fontSize: 11, margin: '2px 0 4px' }}>
                          {shortDev(st.device)} - {fmtTime(st.hlc)}{active ? ' - active now' : ' - trimmed, kept in history'}
                        </div>
                        {!active && (
                          <div>
                            <button style={css.btn} onClick={() => send([{ item: id, field: field as string, value, stamp: phone.stamp() }])}>Keep this version</button>
                            {field === 'title' && (
                              <button style={css.btnAlt} onClick={() => {
                                const copyId = 'item-' + Date.now().toString(36) + '-c';
                                const s = phone.stamp();
                                send([
                                  { item: copyId, field: 'title', value, stamp: s },
                                  { item: copyId, field: 'done', value: 'false', stamp: s },
                                ]);
                              }}>Keep both</button>
                            )}
                          </div>
                        )}
                      </div>
                    );
                    return (
                      <div key={field as string}>
                        <div style={{ color: '#98A2B3', fontSize: 11, margin: '4px 0' }}>Field: <b>{field as string}</b></div>
                        {opt(wv, ws, true, 'w')}
                        {losers.map((l: any, i: number) => opt(l.value, l.stamp, false, 'l' + i))}
                      </div>
                    );
                  })}
                </div>
              )}
            </div>
          );
        })}
        <div style={{ display: 'flex', gap: 8, padding: 12 }}>
          <input
            value={newTitle}
            onChange={e => setNewTitle(e.target.value)}
            onKeyDown={e => e.key === 'Enter' && addItem()}
            aria-label={phone.addr + ' new item'} placeholder="Add an item"
            style={{ flex: 1, minWidth: 0, background: '#1D2939', border: '1px solid #344054', borderRadius: 8, color: '#fff', padding: 10, fontSize: 14 }}
          />
          <button onClick={addItem} style={{ background: '#F59E0B', color: '#101828', border: 'none', borderRadius: 8, fontWeight: 600, padding: '0 16px', cursor: 'pointer' }}>Add</button>
        </div>
      </div>
    </div>
  );
}

export default function App() {
  const { bus, phoneA, phoneB } = useMemo(() => {
    const bus = new Bus();
    return { bus, phoneA: new Phone('Phone A (demo)', bus, () => {}), phoneB: new Phone('Phone B (demo)', bus, () => {}) };
  }, []);
  return (
    <div style={{ fontFamily: 'system-ui, sans-serif', background: '#0B1220', color: '#fff', minHeight: '100vh', padding: 16 }}>
      <h1 style={{ fontSize: 20 }}>itdxdc - webxdc shared list (prototype demo)</h1>
      <p style={{ color: '#98A2B3', fontSize: 13, maxWidth: 720 }}>
        Two SIMULATED phones in one page so the sync is visible without DeltaChat: what you do on
        Phone A appears on Phone B through the same merge engine and update flow the real mini app
        uses. Try it: check off or add an item on one phone, watch the other. The original wrapper has add/check/delete controls but no title-edit control, so adding distinct items does not itself create a same-field conflict. The merge engine retains its conflict-history UI; this limitation is preserved, not presented as a working conflict-generation test.
      </p>
      <p style={{ color: '#F59E0B', fontSize: 12, maxWidth: 720 }}>
        PROTOTYPE with invented sample data. The real mini app (itdxdc-v0.4.xdc) is shared inside a
        DeltaChat group, where Delta Chat itself carries the encrypted updates between members.
        Profiles, co-managing with friends and storage on other networks are design directions, not
        built. The Android widget app stays a separate app.
      </p>
      <p style={{ fontSize: 13 }}>
        <a href={xdcZip} download="itdxdc-v0.4.zip" style={{ color: '#7A5AF8' }}>
          Download itdxdc v0.4 (itdxdc-v0.4.zip, contains itdxdc-v0.4.xdc)
        </a>
        <span style={{ color: '#98A2B3', fontSize: 12 }}> Archived v0.4 package, retained for historical reproduction, not the current release. Each .xdc message is a separate app instance; this download does not upgrade an existing list.</span>
      </p>
      <p style={{ fontSize: 13 }}>
        <a href="https://github.com/irvan-putra/intodo/commit/dcc8257cb1d43b45cb4ec1fd7b784bb5b8558b37" target="_blank" rel="noreferrer" style={{ color: '#7A5AF8' }}>
          Historical source: Intodo at dcc8257
        </a>
        <span style={{ color: '#98A2B3', fontSize: 12 }}>
          {' '}- public prototype source; this page ships the built v0.4 package.
        </span>
      </p>
      <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap' }}>
        <PhoneView phone={phoneA} bus={bus} color="#F59E0B" />
        <PhoneView phone={phoneB} bus={bus} color="#7A5AF8" />
      </div>
    </div>
  );
}
