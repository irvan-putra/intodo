# Node feed encryptor (tools/encrypt_feed.mjs)

Node.js helper that encrypts a JSON feed file for a paired reader app
(ECDH P-256 + HKDF-SHA256 + AES-256-GCM, base64url output).

This is generic tooling: it ships **no keys and no personal data**. The
recipient public key is always passed in by the caller (`--pubkey` or the
`FEED_PUBKEY` environment variable) and comes from your own app pairing.

## Usage

```sh
# encrypt a feed
node encrypt_feed.mjs --in feed.example.json --pubkey <base64url-SPKI> --out payload.txt

# verify the crypto round-trips with a throwaway key (persists nothing)
node encrypt_feed.mjs --selftest
```

## Wire format

`payload = base64url( 65-byte ephemeral public point || 12-byte nonce || ciphertext || 16-byte GCM tag )`

- Key agreement: ephemeral ECDH on P-256 against the recipient's SPKI public key.
- KDF: HKDF-SHA256, 32-byte zero salt, info string `instinct-widget-feed-v1`
  (part of the shared format with the reader app; not a secret).
- Cipher: AES-256-GCM.

`feed.example.json` is a synthetic fixture showing the feed shape
(`generated_at`, `sections[].items[]` with `id/title/note/status`).

Requires Node 16+ (uses `crypto.hkdfSync` and unpadded base64url buffers). No dependencies.

The wire format matches `tools/encrypt_feed.py` (the Python twin in this repo)
and the Android reader app: payloads are interchangeable between the two
helpers. One difference from the Python wrapper: this script encrypts the
input file exactly as given and does not stamp `generated_at`; stamp it
yourself first if your reader needs a fresh generation time.
