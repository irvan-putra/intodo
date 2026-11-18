#!/usr/bin/env python3
"""Fail closed on wrong identity, flags, ABI, or signer; emit no app content."""
import hashlib
import pathlib
import re
import subprocess
import sys
import zipfile


def run(*args):
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT)


class VerificationFailure(AssertionError):
    """Only self-authored codes may cross the verifier boundary."""
    def __init__(self, code):
        self.code = code
        super().__init__(code)


def require(condition, code):
    if not condition:
        raise VerificationFailure(code)


def checked_run(code, *args):
    try:
        return run(*args)
    except Exception:
        raise VerificationFailure(code) from None


def signer(apksigner, path, role='candidate'):
    output = checked_run(role + '_signature_command', apksigner,
                         'verify', '--verbose', '--print-certs', str(path))
    return parse_signer_output(output, role)


def parse_signer_output(output, role='candidate'):
    # SDK34/36: Signer #1; SDK37: V3.0 Signer:. Do not accept arbitrary
    # prefixes, rotated identities, extra certificates, or multiple signers.
    counts = re.findall(r'^Number of signers: (.*)$', output, re.M)
    digest_lines = [line for line in output.splitlines()
                    if 'certificate SHA-256 digest:' in line]
    if counts != ['1'] or len(digest_lines) != 1:
        raise VerificationFailure(role + '_signature_format')
    match = re.fullmatch(
        r'(?:Signer #1|V3\.0 Signer:) certificate SHA-256 digest: ([0-9a-fA-F]{64})',
        digest_lines[0])
    if match is None:
        raise VerificationFailure(role + '_signature_format')
    return match.group(1).lower()


def verify(aapt, apksigner, candidate, reference, version, code):
    require(re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version), "version_input")
    require(re.fullmatch(r"[1-9][0-9]*", code), "code_input")
    text = checked_run('badging_command', aapt, 'dump', 'badging', str(candidate))
    require(re.search(r"^package: name='com.vanipy.intodo' versionCode='" + re.escape(code) + r"' versionName='" + re.escape(version) + r"'", text, re.M), 'identity')
    require("application-label:'intodo'" in text, 'label')
    require("native-code: 'arm64-v8a'" in text, 'badging_abi')
    manifest = checked_run('manifest_command', aapt, 'dump', 'xmltree', str(candidate), 'AndroidManifest.xml')
    require(not re.search(r'android:(debuggable|testOnly).*0xffffffff', manifest), 'debug_or_test_flag')
    require('sharedUserId' not in manifest, 'shared_uid')
    try:
        with zipfile.ZipFile(candidate) as z:
            abis = {n.split('/')[1] for n in z.namelist() if n.startswith('lib/') and n.endswith('.so')}
            require(abis == {'arm64-v8a'}, 'native_abi')
            require('lib/arm64-v8a/librpcsrv.so' in z.namelist(), 'rpc_binary_missing')
            forbidden = re.compile(rb'https?://files\.instinct\.com/[A-Za-z0-9_-]{4,}', re.I)
            for entry in z.infolist():
                if not entry.is_dir():
                    require(not forbidden.search(z.read(entry)), 'private_feed_found')
    except VerificationFailure:
        raise
    except Exception:
        raise VerificationFailure('archive_read') from None
    require(signer(apksigner, candidate, 'candidate') == signer(apksigner, reference, 'reference'), 'signer_mismatch')
    try:
        return hashlib.sha256(pathlib.Path(candidate).read_bytes()).hexdigest()
    except Exception:
        raise VerificationFailure('checksum_read') from None


if __name__ == '__main__':
    if len(sys.argv) != 7:
        raise SystemExit('usage: verify_apk.py AAPT APKSIGNER CANDIDATE REFERENCE VERSION CODE')
    try:
        digest = verify(sys.argv[1], sys.argv[2], pathlib.Path(sys.argv[3]), pathlib.Path(sys.argv[4]), sys.argv[5], sys.argv[6])
    except VerificationFailure as error:
        # Code is constructed only at fixed call sites above. Never print
        # subprocess output, exception text, certificates or APK content.
        raise SystemExit('apk_verification=' + error.code + '; do not distribute')
    except Exception:
        raise SystemExit('apk_verification=unclassified; do not distribute')
    print(digest + '  ' + pathlib.Path(sys.argv[3]).name)
