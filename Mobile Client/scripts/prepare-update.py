"""Validate a signed APK and atomically prepare an internal update release.

Run with the mobile backend virtualenv. Does not build, sign, or upload anything.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from backend.app_updates import Release


def prepare(apk, channel, output, sdk, java, notes='', download_base_url=''):
    apk = apk.resolve(strict=True)
    candidates = sorted((sdk / 'build-tools').glob('*/aapt2*'), reverse=True)
    candidates = [p for p in candidates if p.name in ('aapt2', 'aapt2.exe')]
    if not candidates:
        raise ValueError('Android SDK build-tools with aapt2 are required')
    aapt = candidates[0]
    signer = aapt.parent / 'lib' / 'apksigner.jar'
    signed = subprocess.run([str(java), '-jar', str(signer), 'verify', '--print-certs', str(apk)],
        check=True, capture_output=True, text=True, encoding='utf-8', errors='replace')
    badging = subprocess.run([str(aapt), 'dump', 'badging', str(apk)], check=True, capture_output=True,
        text=True, encoding='utf-8', errors='replace').stdout
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
    sdk_match = re.search(r"^(?:minSdkVersion|sdkVersion):'(\d+)'", badging, re.M)
    if not package or not sdk_match:
        raise ValueError('Cannot read APK version and minimum Android version')
    debuggable = 'application-debuggable' in badging
    if debuggable != (channel == 'debug'):
        raise ValueError('APK debug flag does not match the requested channel')
    if download_base_url:
        url = urlsplit(download_base_url)
        if url.scheme != 'https' or not url.hostname or url.username or url.password or url.query or url.fragment:
            raise ValueError('Download base must be an HTTPS prefix without credentials, query or fragment')
    digest = hashlib.sha256()
    with apk.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    sha = digest.hexdigest()
    release = Release(package_name=package[1], channel=channel, version_code=int(package[2]),
        version_name=package[3], min_sdk=int(sdk_match[1]), sha256=sha, size_bytes=apk.stat().st_size,
        notes=notes, download_url=download_base_url.rstrip('/') + '/' + sha + '.apk' if download_base_url else '')
    output.mkdir(parents=True, exist_ok=True)
    manifest = output / f'update-{channel}.json'
    if manifest.exists():
        old = Release.model_validate_json(manifest.read_text(encoding='utf-8'))
        if old.version_code > release.version_code or (old.version_code == release.version_code and old.sha256 != sha):
            raise ValueError('Increase versionCode before publishing a different APK; downgrades are not allowed')
    # Copy first, manifest last. Readers never see a manifest pointing to partial bytes.
    fd, name = tempfile.mkstemp(prefix='release-', suffix='.tmp', dir=output)
    try:
        with os.fdopen(fd, 'wb') as target, apk.open('rb') as source:
            shutil.copyfileobj(source, target)
        copied = hashlib.sha256(Path(name).read_bytes()).hexdigest()
        if copied != sha:
            raise ValueError('APK changed while preparing release')
        os.replace(name, output / (sha + '.apk'))
    finally:
        Path(name).unlink(missing_ok=True)
    fd, name = tempfile.mkstemp(prefix='manifest-', suffix='.tmp', dir=output)
    try:
        with os.fdopen(fd, 'w', encoding='utf-8', newline='\n') as stream:
            json.dump(release.model_dump(), stream, ensure_ascii=False, indent=2)
            stream.write('\n')
        os.replace(name, manifest)
    finally:
        Path(name).unlink(missing_ok=True)
    fingerprint = next((line for line in signed.stdout.splitlines() if 'certificate SHA-256 digest:' in line), 'Signature verified')
    return release, fingerprint


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--channel', choices=['debug', 'pilot'], required=True)
    parser.add_argument('--output', type=Path, default=ROOT / 'backend' / 'releases')
    parser.add_argument('--sdk', type=Path, default=Path(os.getenv('ANDROID_HOME', str(ROOT / '.tools' / 'sdk'))))
    parser.add_argument('--java', type=Path)
    parser.add_argument('--notes', default='')
    parser.add_argument('--download-base-url', default='')
    args = parser.parse_args()
    java = args.java
    if java is None:
        local = ROOT / '.tools' / 'java-home.txt'
        java_home = os.getenv('JAVA_HOME') or (local.read_text(encoding='utf-8-sig').strip() if local.exists() else '')
        java = Path(java_home) / 'bin' / ('java.exe' if os.name == 'nt' else 'java') if java_home else Path('java')
    release, fingerprint = prepare(args.apk, args.channel, args.output, args.sdk, java, args.notes, args.download_base_url)
    print(f'Prepared {release.channel} {release.version_name} (code {release.version_code}), {release.size_bytes} bytes')
    print('SHA-256: ' + release.sha256)
    print(fingerprint)
    print('Release directory: ' + str(args.output.resolve()))
    if release.download_url:
        print('Upload the SHA-named APK to the HTTPS asset host BEFORE publishing the manifest.')


if __name__ == '__main__':
    main()
