import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from fastapi import FastAPI
from fastapi.testclient import TestClient
from backend.app_updates import router


class AppUpdateTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.env = patch.dict(os.environ, {'WEIGUANG_RELEASE_DIR': self.temp.name})
        self.env.start()
        app = FastAPI()
        app.include_router(router)
        self.client = TestClient(app)
        self.apk = b'synthetic test bytes, not an installable APK'
        self.digest = hashlib.sha256(self.apk).hexdigest()
        self.manifest = dict(schema_version=1, package_name='cn.weiguang.app', channel='debug',
            version_code=3, version_name='0.3.0', min_sdk=29, sha256=self.digest,
            size_bytes=len(self.apk), notes='New update', download_url='')

    def tearDown(self):
        self.client.close()
        self.env.stop()
        self.temp.cleanup()

    def publish(self, **changes):
        self.manifest.update(changes)
        (self.root / 'update-debug.json').write_text(json.dumps(self.manifest), encoding='utf-8')
        (self.root / (self.digest + '.apk')).write_bytes(self.apk)

    def test_absent_and_no_newer_version(self):
        self.assertEqual(self.client.get('/v1/app/update').status_code, 204)
        self.publish()
        for version in (3, 4):
            response = self.client.get(f'/v1/app/update?version_code={version}')
            self.assertEqual(response.status_code, 204)
            self.assertEqual(response.headers['cache-control'], 'no-store')

    def test_new_version_and_immutable_download_without_login(self):
        self.publish()
        response = self.client.get('/v1/app/update?channel=debug&version_code=2')
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), self.manifest)
        artifact = self.client.get(f'/v1/app/releases/{self.digest}.apk')
        self.assertEqual(artifact.content, self.apk)
        self.assertIn('immutable', artifact.headers['cache-control'])
        self.assertEqual(artifact.headers['content-type'], 'application/vnd.android.package-archive')

    def test_channels_are_isolated_and_validated(self):
        self.publish()
        self.assertEqual(self.client.get('/v1/app/update?channel=pilot').status_code, 204)
        self.assertEqual(self.client.get('/v1/app/update?channel=release').status_code, 422)
        self.publish(channel='pilot')
        self.assertEqual(self.client.get('/v1/app/update').status_code, 503)

    def test_incomplete_and_corrupt_release_unavailable(self):
        self.publish(size_bytes=500)
        self.assertEqual(self.client.get('/v1/app/update').status_code, 503)
        (self.root / 'update-debug.json').write_text('{broken', encoding='utf-8')
        self.assertEqual(self.client.get('/v1/app/update').status_code, 503)

    def test_external_https_asset_does_not_require_local_copy(self):
        self.publish(download_url='https://cdn.example.org/app.apk')
        (self.root / (self.digest + '.apk')).unlink()
        self.assertEqual(self.client.get('/v1/app/update').status_code, 200)

    def test_unsafe_download_urls_rejected(self):
        for url in ('http://example.org/app.apk', 'https://user:secret@example.org/a.apk', 'file:///a.apk'):
            with self.subTest(url=url):
                self.publish(download_url=url)
                self.assertEqual(self.client.get('/v1/app/update').status_code, 503)

    def test_artifact_paths_and_html_escaping(self):
        self.publish(notes='<script>alert(1)</script>', version_name='<unsafe>')
        page = self.client.get('/download')
        self.assertNotIn('<script>', page.text)
        self.assertIn('&lt;script&gt;', page.text)
        self.assertIn('&lt;unsafe&gt;', page.text)
        for digest in ('not-a-hash', 'a' * 64, '%2e%2e%2fsecret'):
            self.assertEqual(self.client.get(f'/v1/app/releases/{digest}.apk').status_code, 404)


if __name__ == '__main__':
    unittest.main()
