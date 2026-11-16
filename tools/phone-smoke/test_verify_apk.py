#!/usr/bin/env python3
"""Host regression cases for candidate verification. No signing material."""
import importlib.util
import pathlib
import tempfile
import unittest
import subprocess
import sys
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
    def test_boundary_codes_never_copy_command_failure_text(self):
        for role in ['candidate', 'reference']:
            with patch.object(v, 'run', side_effect=RuntimeError('SECRET')):
                with self.assertRaises(v.VerificationFailure) as caught:
                    v.signer('apksigner', self.apk, role)
                self.assertEqual(caught.exception.code, role + '_signature_command')
            with patch.object(v, 'run', return_value='SECRET malformed cert'):
                with self.assertRaises(v.VerificationFailure) as caught:
                    v.signer('apksigner', self.apk, role)
                self.assertEqual(caught.exception.code, role + '_signature_format')
        self.certs[self.ref] = 'b' * 64
        with self.assertRaises(v.VerificationFailure) as caught: self.verify()
        self.assertEqual(caught.exception.code, 'signer_mismatch')
    def test_cli_command_failure_is_bounded(self):
        cli = subprocess.run([sys.executable, str(pathlib.Path(v.__file__)),
            '/missing/SECRET-aapt', '/missing/SECRET-apksigner',
            '/missing/SECRET-candidate', '/missing/SECRET-reference'],
            text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.assertNotEqual(cli.returncode, 0)
        self.assertEqual(cli.stdout, '')
        self.assertEqual(cli.stderr, 'apk_verification=badging_command; do not distribute\n')
    def test_fixed_failure_codes(self):
        for needle, replacement, code in [
            ('com.vanipy.intodo', 'com.instinct.widget', 'identity'),
            ('Intodo', 'itdapk', 'label'), ('arm64-v8a', 'x86', 'badging_abi')]:
            self.badging = GOOD.replace(needle, replacement)
            with self.assertRaises(v.VerificationFailure) as caught: self.verify()
            self.assertEqual(caught.exception.code, code)
        self.badging = GOOD
        self.write({'assets/private.txt': b'https://files.instinct.com/synthetic-fixture'})
        with self.assertRaises(v.VerificationFailure) as caught: self.verify()
        self.assertEqual(caught.exception.code, 'private_feed_found')
    def test_unsigned_or_multisigner(self):
        for response in ['', ('Signer #1 certificate SHA-256 digest: ' + CERT + '\n') * 2]:
            with patch.object(v, 'run', return_value=response):
                with self.assertRaises(v.VerificationFailure): v.signer('apksigner', self.apk)

if __name__ == '__main__': unittest.main()
