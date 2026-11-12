// itdxdc canonical ops digest (browser). Byte-identical to the bot's
// delivery_bot.py canonical_digest and the v4 reference digest-web.mjs.
// Corruption-integrity only: this NEVER authenticates the bot.
// Uses crypto.subtle when available; otherwise a portable sync SHA-256
// (webxdc clients do not guarantee a secure context on every platform).
(function (g) {
  'use strict';

  function cmpCodePoint(a, b) {
    const ai = Array.from(a), bi = Array.from(b);
    const n = Math.min(ai.length, bi.length);
    for (let i = 0; i < n; i++) {
      const x = ai[i].codePointAt(0), y = bi[i].codePointAt(0);
      if (x !== y) return x < y ? -1 : 1;
    }
    return ai.length - bi.length;
  }

  function pyString(s) {
    let out = '"';
    for (const ch of s) {
      const cp = ch.codePointAt(0);
      if (ch === '"') out += '\\"';
      else if (ch === '\\') out += '\\\\';
      else if (ch === '\b') out += '\\b';
      else if (ch === '\f') out += '\\f';
      else if (ch === '\n') out += '\\n';
      else if (ch === '\r') out += '\\r';
      else if (ch === '\t') out += '\\t';
      else if (cp < 0x20 || cp === 0x7f) out += '\\u' + cp.toString(16).padStart(4, '0');
      else if (cp < 0x80) out += ch;
      else if (cp < 0x10000) out += '\\u' + cp.toString(16).padStart(4, '0');
      else {
        const v = cp - 0x10000;
        out += '\\u' + (0xd800 + (v >> 10)).toString(16) + '\\u' + (0xdc00 + (v & 0x3ff)).toString(16);
      }
    }
    return out + '"';
  }

  function stableStringify(v) {
    if (v === null) return 'null';
    if (typeof v === 'boolean') return v ? 'true' : 'false';
    if (typeof v === 'number') return String(v);
    if (typeof v === 'string') return pyString(v);
    if (Array.isArray(v)) return '[' + v.map(stableStringify).join(',') + ']';
    const keys = Object.keys(v).sort(cmpCodePoint);
    return '{' + keys.map(k => pyString(k) + ':' + stableStringify(v[k])).join(',') + '}';
  }

  function validateOp(op) {
    if (op === null || typeof op !== 'object' || Array.isArray(op)) throw new Error('op must be an object');
    if (typeof op.item !== 'string' || op.item === '') throw new Error('op.item must be a non-empty string');
    if (typeof op.field !== 'string' || op.field === '') throw new Error('op.field must be a non-empty string');
    const s = op.stamp;
    if (s === null || typeof s !== 'object' || Array.isArray(s)) throw new Error('op.stamp must be an object');
    if (typeof s.hlc !== 'number' || !Number.isSafeInteger(s.hlc)) throw new Error('op.stamp.hlc must be a safe integer');
    if (typeof s.device !== 'string' || s.device === '') throw new Error('op.stamp.device must be a non-empty string');
    if (!('value' in op)) throw new Error('op.value key must be present (use null)');
    const v = op.value;
    if (v !== null && typeof v !== 'string' && typeof v !== 'boolean'
        && !(typeof v === 'number' && Number.isSafeInteger(v))) {
      throw new Error('op.value must be a string, safe integer, bool, or null');
    }
    return op;
  }

  function canonicalString(ops) {
    const canon = ops.slice().sort((a, b) =>
      cmpCodePoint(a.item || '', b.item || '') ||
      cmpCodePoint(a.field || '', b.field || '') ||
      (((a.stamp && a.stamp.hlc) || 0) - ((b.stamp && b.stamp.hlc) || 0)) ||
      cmpCodePoint((a.stamp && a.stamp.device) || '', (b.stamp && b.stamp.device) || ''));
    return stableStringify(canon);
  }

  // --- portable sync SHA-256 (fallback when crypto.subtle is unavailable) ---
  function sha256Sync(bytes) {
    const K = [0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
      0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
      0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
      0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
      0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
      0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
      0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
      0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2];
    let h0=0x6a09e667,h1=0xbb67ae85,h2=0x3c6ef372,h3=0xa54ff53a,h4=0x510e527f,h5=0x9b05688c,h6=0x1f83d9ab,h7=0x5be0cd19;
    const l = bytes.length;
    const bitLen = l * 8;
    const padded = (l + 9 + 63) & ~63;
    const m = new Uint8Array(padded);
    m.set(bytes); m[l] = 0x80;
    const dv = new DataView(m.buffer);
    dv.setUint32(padded - 8, Math.floor(bitLen / 0x100000000));
    dv.setUint32(padded - 4, bitLen >>> 0);
    const w = new Int32Array(64);
    const rr = (x, n) => (x >>> n) | (x << (32 - n));
    for (let off = 0; off < padded; off += 64) {
      for (let i = 0; i < 16; i++) w[i] = dv.getUint32(off + i * 4);
      for (let i = 16; i < 64; i++) {
        const s0 = rr(w[i-15],7) ^ rr(w[i-15],18) ^ (w[i-15] >>> 3);
        const s1 = rr(w[i-2],17) ^ rr(w[i-2],19) ^ (w[i-2] >>> 10);
        w[i] = (w[i-16] + s0 + w[i-7] + s1) | 0;
      }
      let a=h0,b=h1,c=h2,d=h3,e=h4,f=h5,gg=h6,hh=h7;
      for (let i = 0; i < 64; i++) {
        const S1 = rr(e,6) ^ rr(e,11) ^ rr(e,25);
        const ch = (e & f) ^ (~e & gg);
        const t1 = (hh + S1 + ch + K[i] + w[i]) | 0;
        const S0 = rr(a,2) ^ rr(a,13) ^ rr(a,22);
        const maj = (a & b) ^ (a & c) ^ (b & c);
        const t2 = (S0 + maj) | 0;
        hh=gg; gg=f; f=e; e=(d+t1)|0; d=c; c=b; b=a; a=(t1+t2)|0;
      }
      h0=(h0+a)|0; h1=(h1+b)|0; h2=(h2+c)|0; h3=(h3+d)|0;
      h4=(h4+e)|0; h5=(h5+f)|0; h6=(h6+gg)|0; h7=(h7+hh)|0;
    }
    return [h0,h1,h2,h3,h4,h5,h6,h7].map(h => (h >>> 0).toString(16).padStart(8, '0')).join('');
  }

  function toHex(buf) {
    return Array.from(new Uint8Array(buf), b => b.toString(16).padStart(2, '0')).join('');
  }

  // Returns a Promise<string> hex digest.
  function canonicalDigest(ops) {
    const bytes = new TextEncoder().encode(canonicalString(ops));
    if (g.crypto && g.crypto.subtle && g.crypto.subtle.digest) {
      return g.crypto.subtle.digest('SHA-256', bytes).then(toHex);
    }
    return Promise.resolve(sha256Sync(bytes));
  }

  // Verify a restore envelope: every op must be in-domain and the digest
  // must match the canonical ops digest. Returns Promise<boolean>.
  function verifyEnvelope(env) {
    try { env.ops.forEach(validateOp); } catch (e) { return Promise.resolve(false); }
    if (typeof env.digest !== 'string' || !/^[0-9a-f]{64}$/.test(env.digest)) return Promise.resolve(false);
    return canonicalDigest(env.ops).then(d => d === env.digest);
  }

  g.ItdDigest = { canonicalString, canonicalDigest, validateOp, verifyEnvelope, sha256Sync };
})(typeof window !== 'undefined' ? window : globalThis);
