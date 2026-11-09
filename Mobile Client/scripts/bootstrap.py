"""Install portable build tools inside the project, without changing system PATH."""
import concurrent.futures
import hashlib
import json
from pathlib import Path
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / '.tools'
TOOLS.mkdir(exist_ok=True)


def fetch(url):
    request = urllib.request.Request(url, headers={'User-Agent': 'Weiguang-build/0.1'})
    return urllib.request.urlopen(request, timeout=120)


def install(name, url, checksum, target):
    target = TOOLS / target
    marker = target / '.complete'
    if marker.exists():
        return str(target)
    archive = TOOLS / (name + '.zip')
    if not archive.exists():
        with fetch(url) as response, archive.open('wb') as output:
            while chunk := response.read(1024 * 1024):
                output.write(chunk)
    with archive.open('rb') as downloaded:
        digest = hashlib.file_digest(downloaded, 'sha256').hexdigest()
    if digest.lower() != checksum.strip().lower():
        raise RuntimeError(f'{name} checksum mismatch, remove the partial archive and retry')
    target.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive) as package:
        for member in package.infolist():
            resolved = (target / member.filename).resolve()
            if not resolved.is_relative_to(target.resolve()):
                raise RuntimeError('Unsafe archive path')
        package.extractall(target)
    marker.write_text('verified\n', encoding='utf-8')
    print(f'{name} ready', flush=True)
    return str(target)


if __name__ == '__main__':
    with fetch('https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse') as response:
        jdk = json.load(response)[0]['binary']['package']
    with fetch('https://services.gradle.org/distributions/gradle-8.11.1-bin.zip.sha256') as response:
        gradle_hash = response.read().decode().strip()
    jobs = [
        ('jdk', jdk['link'], jdk['checksum'], 'jdk'),
        ('gradle', 'https://services.gradle.org/distributions/gradle-8.11.1-bin.zip', gradle_hash, 'gradle'),
        ('android-cli', 'https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip',
         '90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a', 'sdk/cmdline-tools/bootstrap'),
    ]
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        list(pool.map(lambda args: install(*args), jobs))
    sdk_tools = TOOLS / 'sdk/cmdline-tools'
    if not (sdk_tools / 'latest').exists():
        (sdk_tools / 'bootstrap/cmdline-tools').rename(sdk_tools / 'latest')
    java_home = next((TOOLS / 'jdk').glob('jdk-*'))
    (TOOLS / 'java-home.txt').write_text(str(java_home), encoding='utf-8')
    print('Portable tool downloads complete. Run scripts/build.ps1 next.', flush=True)
