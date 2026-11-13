// Notice-tap route guard (host-only): every status update that creates a
// chat notice (info set) must carry an href with a REAL FILE part, e.g.
// 'index.html#a'. A pure-fragment href like '#a' makes the messenger's
// webview request "/", which is not a zip entry, and the user sees
// 'Webxdc Request Error: "/" not found' instead of the app.
// Run: node test/notice-route.test.mjs   (from webxdc/)
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const src = readFileSync(join(here, '..', 'js', 'app.js'), 'utf8');

let pass = 0;
const ok = (c, n) => { if (!c) { console.error('FAIL: ' + n); process.exit(1); } pass++; console.log(n + ' ok'); };

const calls = [...src.matchAll(/sendUpdate\(\s*\{([^}]*)\}/g)].map(m => m[1]);
ok(calls.length > 0, 'found sendUpdate calls');
for (const [i, body] of calls.entries()) {
  if (!/\binfo\s*:/.test(body)) continue;  // no notice, no tap route
  const m = body.match(/\bhref\s*:\s*'([^']+)'/);
  ok(m && /[\w.-]+\.html(#|$)/.test(m[1]),
     'notice sendUpdate #' + i + ' href has a real file part: ' + (m && m[1]));
}
console.log('ALL NOTICE-ROUTE TESTS PASS (' + pass + ' assertions)');
