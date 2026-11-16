#!/usr/bin/env python3
"""Host regression cases for candidate verification. No signing material."""
import importlib.util
import pathlib
import tempfile
import unittest
import zipfile
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('verifier', pathlib.Path(__file__).with_name('verify_apk.py'))
v = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v)
GOOD = "package: name='com.vanipy.intodo' versionCode='26' versionName='0.5.11-smoke.1'\napplication-label:'Intodo'\nnative-code: 'arm64-v8a'\n"
CERT = 'a' * 64

class VerifyTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.apk = pathlib.Path(self.tmp.name) / 'candidate.apk'
        self.ref = pathlib.Path(self.tmp.name) / 'reference.apk'
        self.badging, self.manifest = GOOD, 'manifest'
        self.certs = {self.apk: CERT, self.ref: CERT}
        self.write()
    def write(self, extra=None):
        with zipfile.ZipFile(self.apk, 'w') as z:
            z.writestr('lib/arm64-v8a/librpcsrv.so', b'fixture')
            for name, data in (extra or {}).items(): z.writestr(name, data)
    def run_tool(self, *args):
        if args[0] == 'aapt': return self.badging if args[2] == 'badging' else self.manifest
        return 'Signer #1 certificate SHA-256 digest: ' + self.certs[pathlib.Path(args[-1])] + '\n'
    def verify(self):
        with patch.object(v, 'run', self.run_tool):
            return v.verify('aapt', 'apksigner', self.apk, self.ref)
    def test_good(self): self.assertEqual(len(self.verify()), 64)
    def test_identity_label_version(self):
        for a,b in [('com.vanipy.intodo','com.instinct.widget'), ('Intodo','itdapk'),
                    ("versionCode='26'","versionCode='25'"), ('0.5.11-smoke.1','0.5.10')]:
            self.badging = GOOD.replace(a,b)
            with self.assertRaises(AssertionError): self.verify()
    def test_flags(self):
        for flag in ['debuggable','testOnly']:
            self.manifest = 'A: android:' + flag + '(0x1)=(type 0x12)0xffffffff'
            with self.assertRaises(AssertionError): self.verify()
        self.manifest = 'sharedUserId'
        with self.assertRaises(AssertionError): self.verify()
    def test_abi(self):
        self.write({'lib/x86/libfake.so': b'fixture'})
        with self.assertRaises(AssertionError): self.verify()
    def test_private_content(self):
        self.write({'assets/source.txt': b'https://files.instinct.com/synthetic-fixture'})
        with self.assertRaises(AssertionError): self.verify()
    def test_signer_mismatch(self):
        self.certs[self.apk] = 'b' * 64
        with self.assertRaises(AssertionError): self.verify()
    def test_unsigned_or_multisigner(self):
        for response in ['', ('Signer #1 certificate SHA-256 digest: ' + CERT + '\n') * 2]:
            with patch.object(v, 'run', return_value=response):
                with self.assertRaises(ValueError): v.signer('apksigner', self.apk)

if __name__ == '__main__': unittest.main()
