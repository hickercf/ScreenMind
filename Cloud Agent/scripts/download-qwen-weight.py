"""Download the pinned official weight with size/hash verification and bounded retries."""
from pathlib import Path
import hashlib
import json
import time
import httpx

MODEL = 'Qwen/Qwen3-Embedding-0.6B'
REVISION = '97b0c614be4d77ee51c0cef4e5f07c00f9eb65b3'
root = Path('artifacts/models/Qwen3-Embedding-0.6B')
root.mkdir(parents=True, exist_ok=True)
url = f'https://huggingface.co/{MODEL}/resolve/{REVISION}/model.safetensors'
with httpx.Client(timeout=httpx.Timeout(30, connect=15), follow_redirects=True) as client:
    meta = client.head(url, follow_redirects=False)
    meta.raise_for_status() if meta.status_code >= 400 else None
    expected_hash = meta.headers['x-linked-etag'].strip('"')
    expected_size = int(meta.headers['x-linked-size'])
    partial = root / 'model.safetensors.partial'
    started = time.monotonic()
    for attempt in range(4):
        offset = partial.stat().st_size if partial.exists() else 0
        if offset == expected_size:
            break
        try:
            with client.stream('GET', url, headers={'Range': f'bytes={offset}-'}) as response:
                response.raise_for_status()
                append = response.status_code == 206
                if append and not response.headers.get('content-range', '').startswith(f'bytes {offset}-'):
                    raise ValueError('Unexpected range response')
                if not append:
                    offset = 0
                last = time.monotonic()
                with partial.open('ab' if append else 'wb') as output:
                    for chunk in response.iter_bytes(1024*1024):
                        output.write(chunk)
                        offset += len(chunk)
                        if time.monotonic()-last > 10:
                            print(json.dumps({'downloaded_mb': round(offset/1e6), 'total_mb': round(expected_size/1e6)}), flush=True)
                            last = time.monotonic()
            if partial.stat().st_size == expected_size:
                break
        except (httpx.HTTPError, OSError) as exc:
            print(json.dumps({'attempt': attempt+1, 'error_type': type(exc).__name__}), flush=True)
    assert partial.stat().st_size == expected_size, 'Incomplete model weight'
    with partial.open('rb') as handle:
        actual = hashlib.file_digest(handle, 'sha256').hexdigest()
    assert actual == expected_hash, 'Model weight checksum mismatch'
    partial.replace(root/'model.safetensors')
    record = {'model': MODEL, 'revision': REVISION, 'sha256': actual, 'bytes': expected_size,
              'download_seconds': round(time.monotonic()-started, 2), 'origin': 'huggingface.co/Qwen'}
    (root/'weight-verification.json').write_text(json.dumps(record, indent=2), encoding='utf-8')
    print(json.dumps(record), flush=True)
