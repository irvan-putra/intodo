// webxdc-mock.js - DEV-ONLY simulation of the webxdc messaging layer.
// Active ONLY when window.webxdc is undefined AND a mock address is set
// (window.WEBXDC_MOCK_ADDR or ?mock=<name> in the URL). In real DeltaChat the
// messenger provides window.webxdc and this file is inert.
// Included in the .xdc but inert wherever the messenger provides window.webxdc.
(function () {
  if (window.webxdc !== undefined) return;
  const q = new URLSearchParams(location.search);
  const me = window.WEBXDC_MOCK_ADDR || q.get('mock');
  if (!me) return;

  // storage may be unavailable (sandboxed demo iframes): fall back to memory
  const mem = [];
  const store = {
    read() {
      try { return JSON.parse(localStorage.getItem('webxdc-mock-log') || '[]'); }
      catch (e) { return mem; }
    },
    append(u) {
      mem.push(u);
      try {
        const l = JSON.parse(localStorage.getItem('webxdc-mock-log') || '[]');
        l.push(u);
        localStorage.setItem('webxdc-mock-log', JSON.stringify(l));
      } catch (e) { /* memory only */ }
    },
  };

  let bc = null;
  try { bc = ('BroadcastChannel' in window) ? new BroadcastChannel('webxdc-mock') : null; }
  catch (e) { bc = null; }
  const listeners = [];
  if (bc) bc.onmessage = (ev) => listeners.forEach(cb => cb(ev.data));

  window.webxdc = {
    selfAddr: me,
    selfName: me,
    isBroadcast: true,
    sendUpdate(update) {
      store.append(update);
      listeners.forEach(cb => cb(update));
      if (bc) bc.postMessage(update);
    },
    setUpdateListener(cb, serial) {
      listeners.push(cb);
      store.read().slice(serial || 0).forEach(cb);
      return Promise.resolve();
    },
  };
})();
