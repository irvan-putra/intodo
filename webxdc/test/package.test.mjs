// Packaging coherence (host-only): the .xdc file list in package.sh must
// cover every local script the HTML entry points reference, and versions
// must agree across manifest.toml, js/app.js and the index.html badge.
// Run: node test/package.test.mjs   (from webxdc/)
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const SRC = join(here, '..');
const read = f => readFileSync(join(SRC, f), 'utf8');

let pass = 0;
const ok = (c, n) => { if (!c) { console.error('FAIL: ' + n); process.exit(1); } pass++; console.log(n + ' ok'); };

const sh = read('package.sh');
const packed = [...sh.matchAll(/\b(js\/[\w.-]+\.js|manifest\.toml|icon\.png|index\.html|canvas\.html)\b/g)]
  .map(m => m[1]);

for (const html of ['index.html', 'canvas.html']) {
  const srcs = [...read(html).matchAll(/<script src="(js\/[\w.-]+)"/g)].map(m => m[1]);
  for (const s of srcs) {
    ok(packed.includes(s), html + ' script packed: ' + s);
  }
}
for (const f of ['manifest.toml', 'icon.png', 'index.html', 'canvas.html']) {
  ok(packed.includes(f), 'entry packed: ' + f);
}
ok(!packed.includes('package.sh') && !/test\//.test(packed.join(' ')),
   'no dev/test files packed');

const mv = read('manifest.toml').match(/^version = "(.+)"$/m)[1];
const av = read('js/app.js').match(/APP_VERSION = '(.+)'/)[1];
const badge = read('index.html').match(/<span[^>]*>v([^<]+)<\/span>/)[1];
ok(mv === av, 'manifest version == APP_VERSION (' + mv + ')');
ok('v' + mv === badge || badge === mv, 'index badge matches manifest (' + badge + ')');
ok(!/itdxdc-v0\.4\.xdc/.test(sh), 'output name derives from manifest, not hardcoded');

console.log('ALL PACKAGE TESTS PASS (' + pass + ' assertions)');
