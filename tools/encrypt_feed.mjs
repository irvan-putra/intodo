#!/usr/bin/env node
// encrypt_feed.mjs - encrypt a JSON feed file for a paired reader app.
// Generic CLI: the recipient public key is always supplied by the caller.
// Usage:
//   node encrypt_feed.mjs --in feed.json --pubkey <base64url-SPKI> [--out payload.txt]
//   node encrypt_feed.mjs --selftest
// The key may also come from the FEED_PUBKEY environment variable.
import fs from 'fs';
import crypto from 'crypto';
import { pathToFileURL } from 'url';

// HKDF info string: part of the shared wire format with the reader app.
const DEFAULT_INFO = 'instinct-widget-feed-v1';

function b64d(s){ return Buffer.from(s.replace(/-/g,'+').replace(/_/g,'/'), 'base64'); }

export function encryptFeed(plain, pubB64, info = DEFAULT_INFO){
  const spki = b64d(pubB64);
  const point = spki.subarray(spki.length - 65);
  if (point[0] !== 4) throw new Error('public key is not an uncompressed P-256 point');
  const eph = crypto.createECDH('prime256v1');
  eph.generateKeys();
  const shared = eph.computeSecret(point);
  const key = Buffer.from(crypto.hkdfSync('sha256', shared, Buffer.alloc(32), Buffer.from(info, 'utf8'), 32));
  const nonce = crypto.randomBytes(12);
  const c = crypto.createCipheriv('aes-256-gcm', key, nonce);
  const ct = Buffer.concat([c.update(plain, 'utf8'), c.final()]);
  // payload = 65-byte ephemeral public point | 12-byte nonce | ciphertext | 16-byte tag
  const blob = Buffer.concat([eph.getPublicKey(null, 'uncompressed'), nonce, ct, c.getAuthTag()]);
  return blob.toString('base64url');
}

export function decryptFeed(payload, privJwkD, info = DEFAULT_INFO){
  const blob = b64d(payload);
  const dh = crypto.createECDH('prime256v1');
  dh.setPrivateKey(Buffer.from(privJwkD, 'base64url'));
  const shared = dh.computeSecret(blob.subarray(0, 65));
  const key = Buffer.from(crypto.hkdfSync('sha256', shared, Buffer.alloc(32), Buffer.from(info, 'utf8'), 32));
  const dec = crypto.createDecipheriv('aes-256-gcm', key, blob.subarray(65, 77));
  dec.setAuthTag(blob.subarray(blob.length - 16));
  return Buffer.concat([dec.update(blob.subarray(77, blob.length - 16)), dec.final()]).toString('utf8');
}

function arg(name){ const i = process.argv.indexOf('--' + name); return i >= 0 ? process.argv[i + 1] : null; }

// Run the CLI only when invoked directly, not when imported as a module.
const isMain = process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href;

if (isMain && process.argv.includes('--selftest')){
  const pair = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const pub = pair.publicKey.export({ format: 'der', type: 'spki' }).toString('base64url');
  const privD = pair.privateKey.export({ format: 'jwk' }).d;
  const pt = JSON.stringify({ hello: 'feed', n: 42 });
  const out = decryptFeed(encryptFeed(pt, pub), privD);
  if (out !== pt) throw new Error('roundtrip FAIL');
  console.log('selftest OK (ephemeral key, nothing persisted)');
  process.exit(0);
}

if (isMain) {
  const inFile = arg('in');
  const pub = arg('pubkey') || process.env.FEED_PUBKEY;
  if (!inFile || !pub){
    console.error('need --in <feed.json> and --pubkey <base64url-SPKI> (or FEED_PUBKEY), or --selftest');
    process.exit(2);
  }
  const payload = encryptFeed(fs.readFileSync(inFile, 'utf8'), pub);
  const outFile = arg('out');
  if (outFile) { fs.writeFileSync(outFile, payload); console.log('wrote', outFile, payload.length, 'chars'); }
  else console.log(payload);
}
