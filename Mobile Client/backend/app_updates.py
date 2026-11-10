"""Public, immutable internal-test APK distribution; never exposes model credentials."""
from html import escape
import json
import os
from pathlib import Path
from typing import Literal
from urllib.parse import urlsplit

from fastapi import APIRouter, HTTPException, Query, Response
from fastapi.responses import FileResponse, HTMLResponse
from pydantic import BaseModel, ConfigDict, Field, field_validator


class Release(BaseModel):
    model_config = ConfigDict(extra='forbid')
    schema_version: Literal[1] = 1
    package_name: Literal['cn.weiguang.app'] = 'cn.weiguang.app'
    channel: Literal['debug', 'pilot']
    version_code: int = Field(ge=1)
    version_name: str = Field(min_length=1, max_length=40)
    min_sdk: int = Field(ge=29)
    sha256: str = Field(pattern=r'^[a-f0-9]{64}$')
    size_bytes: int = Field(ge=1, le=200 * 1024 * 1024)
    notes: str = Field(default='', max_length=4000)
    download_url: str = Field(default='', max_length=2000)

    @field_validator('download_url')
    @classmethod
    def https_or_local(cls, value):
        if not value:
            return value
        uri = urlsplit(value)
        if uri.scheme != 'https' or not uri.hostname or uri.username or uri.password or uri.fragment:
            raise ValueError('Remote downloads require an HTTPS URL without credentials or fragment')
        return value


def release_root():
    return Path(os.getenv('WEIGUANG_RELEASE_DIR', str(Path(__file__).with_name('releases')))).resolve()


def read_release(channel):
    path = release_root() / f'update-{channel}.json'
    if not path.exists():
        return None
    try:
        if path.stat().st_size > 20000:
            raise ValueError('Manifest too large')
        release = Release.model_validate(json.loads(path.read_text(encoding='utf-8')))
        if release.channel != channel:
            raise ValueError('Channel mismatch')
        if not release.download_url:
            apk = release_root() / (release.sha256 + '.apk')
            if not apk.is_file() or apk.is_symlink() or apk.stat().st_size != release.size_bytes:
                raise ValueError('APK missing or incomplete')
        return release
    except (ValueError, OSError):
        raise HTTPException(503, '当前更新包尚未准备完整，请稍后再试') from None


router = APIRouter(tags=['App updates'])


@router.get('/v1/app/update')
def latest(channel: Literal['debug', 'pilot'] = 'debug', version_code: int = Query(default=0, ge=0)):
    release = read_release(channel)
    if release is None or release.version_code <= version_code:
        return Response(status_code=204, headers={'Cache-Control': 'no-store'})
    from fastapi.responses import JSONResponse
    return JSONResponse(release.model_dump(), headers={'Cache-Control': 'no-store'})


@router.get('/v1/app/releases/{digest}.apk')
def artifact(digest: str):
    import re
    if not re.fullmatch('[a-f0-9]{64}', digest):
        raise HTTPException(404, '安装包不存在')
    root = release_root()
    path = root / (digest + '.apk')
    if not path.is_file() or path.is_symlink():
        raise HTTPException(404, '安装包不存在')
    return FileResponse(path, media_type='application/vnd.android.package-archive',
        filename='weiguang-' + digest[:12] + '.apk',
        headers={'Cache-Control': 'public, max-age=31536000, immutable', 'X-Content-Type-Options': 'nosniff'})


@router.get('/download', response_class=HTMLResponse)
def download_page():
    blocks = []
    for channel, label in [('debug', '通用内测版'), ('pilot', 'ARM64 轻量内测版')]:
        release = read_release(channel)
        if release:
            url = release.download_url or '/v1/app/releases/' + release.sha256 + '.apk'
            blocks.append(f'<section><h2>{label} {escape(release.version_name)}</h2>'
                f'<p>{release.size_bytes / 1024 / 1024:.1f} MB</p>'
                f'<pre>{escape(release.notes)}</pre><a href="{escape(url, quote=True)}">下载安装包</a></section>')
    body = ''.join(blocks) or '<p>尚未发布安装包，请联系项目维护者。</p>'
    return HTMLResponse('<!doctype html><html lang="zh-CN"><meta charset="utf-8">'
        '<meta name="viewport" content="width=device-width, initial-scale=1"><title>微光下载</title>'
        '<style>body{max-width:640px;margin:40px auto;padding:24px;font:18px/1.7 sans-serif;background:#f4f6f2;color:#17382d}section{margin:28px 0}pre{white-space:pre-wrap;font:inherit}a{display:inline-block;padding:12px 24px;background:#21614b;color:white;border-radius:12px}</style>'
        '<h1>微光 · 内测下载</h1><p>首次安装后，可在应用中检查和下载后续更新。安装需要系统确认。</p>' + body + '</html>',
        headers={'Cache-Control': 'no-store', 'Content-Security-Policy': "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'"})
