#!/usr/bin/env python3
"""Encrypt a widget feed JSON to the phone's public key.

Usage: python3 encrypt_feed.py PUBKEY_B64URL < feed.json
Prints the payload to embed in the feed File page as: IFW1.<output>
The page constant lives in src/App.tsx of the widget-feed File project
(file-01M76GMYFDV4NXSD7SQNWFS0AV). Rebuild + publish the File after changes.
"""
import base64, json, os, sys
from datetime import datetime, timezone
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

def b64d(s):
    return base64.urlsafe_b64decode(s + '=' * (-len(s) % 4))

def encrypt(spki_b64url, plaintext):
    pub = serialization.load_der_public_key(b64d(spki_b64url))
    eph = ec.generate_private_key(ec.SECP256R1())
    shared = eph.exchange(ec.ECDH(), pub)
    key = HKDF(algorithm=hashes.SHA256(), length=32, salt=b'\x00' * 32,
               info=b'instinct-widget-feed-v1').derive(shared)
    nonce = os.urandom(12)
    ct = AESGCM(key).encrypt(nonce, plaintext, None)
    ep = eph.public_key().public_bytes(serialization.Encoding.X962,
                                       serialization.PublicFormat.UncompressedPoint)
    return base64.urlsafe_b64encode(ep + nonce + ct).decode().rstrip('=')

if __name__ == '__main__':
    # Always stamp a fresh UTC-Z generation time: every publish is a new generation,
    # which is what lets phones reject stale or replayed feeds.
    data = json.loads(sys.stdin.buffer.read())
    data['generated_at'] = datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')
    print(encrypt(sys.argv[1].strip(), json.dumps(data).encode()))
