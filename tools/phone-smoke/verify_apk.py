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


def signer(apksigner, path):
    output = run(apksigner, 'verify', '--print-certs', str(path))
    matches = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$', output, re.M)
    if len(matches) != 1:
        raise ValueError('expected one verified signer')
    return matches[0].lower()


def verify(aapt, apksigner, candidate, reference):
    text = run(aapt, 'dump', 'badging', str(candidate))
    assert re.search(r"^package: name='com.vanipy.intodo' versionCode='26' versionName='0.5.11-smoke.1'", text, re.M), 'wrong package/version'
    assert "application-label:'Intodo'" in text, 'wrong label'
    assert "native-code: 'arm64-v8a'" in text, 'wrong ABI'
    manifest = run(aapt, 'dump', 'xmltree', str(candidate), 'AndroidManifest.xml')
    assert not re.search(r'android:(debuggable|testOnly).*0xffffffff', manifest), 'debug/test APK'
    assert 'sharedUserId' not in manifest, 'shared UID forbidden'
    with zipfile.ZipFile(candidate) as z:
        abis = {n.split('/')[1] for n in z.namelist() if n.startswith('lib/') and n.endswith('.so')}
        assert abis == {'arm64-v8a'}, 'unexpected native ABI'
        assert 'lib/arm64-v8a/librpcsrv.so' in z.namelist(), 'RPC binary missing'
        forbidden = re.compile(rb'https?://files\.instinct\.com/[A-Za-z0-9_-]{4,}', re.I)
        for entry in z.infolist():
            if not entry.is_dir():
                assert not forbidden.search(z.read(entry)), 'private feed URL in APK'
    assert signer(apksigner, candidate) == signer(apksigner, reference), 'signer differs from reference release'
    return hashlib.sha256(pathlib.Path(candidate).read_bytes()).hexdigest()


if __name__ == '__main__':
    if len(sys.argv) != 5:
        raise SystemExit('usage: verify_apk.py AAPT APKSIGNER CANDIDATE REFERENCE')
    try:
        digest = verify(sys.argv[1], sys.argv[2], pathlib.Path(sys.argv[3]), pathlib.Path(sys.argv[4]))
    except Exception:
        raise SystemExit('APK verification failed; do not distribute')
    print(digest + '  ' + pathlib.Path(sys.argv[3]).name)
