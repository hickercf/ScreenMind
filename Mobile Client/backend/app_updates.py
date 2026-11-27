"""Public, immutable internal-test APK distribution; never exposes model credentials."""
from html import escape
import json
import os
import re
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
EDITIONS = {
    'debug': ('通用内测版', 'universal', '不确定手机的处理器型号时，选这一版。'),
    'pilot': ('ARM64 轻量内测版', 'arm64', '适用于 ARM64 安卓手机，下载文件更小。'),
}


@router.get('/v1/app/update')
def latest(channel: Literal['debug', 'pilot'] = 'debug', version_code: int = Query(default=0, ge=0)):
    release = read_release(channel)
    if release is None or release.version_code <= version_code:
        return Response(status_code=204, headers={'Cache-Control': 'no-store'})
    from fastapi.responses import JSONResponse
    return JSONResponse(release.model_dump(), headers={'Cache-Control': 'no-store'})


def sakura_preview():
    root = release_root()
    manifest = root / 'preview-sakura.json'
    try:
        if manifest.stat().st_size > 2048:
            return None
        data = json.loads(manifest.read_text(encoding='utf-8'))
        sha, size, version = data['sha256'], data['size_bytes'], data['version_name']
        if not re.fullmatch(r'[a-f0-9]{64}', sha) or not re.fullmatch(r'\d+\.\d+\.\d+', version):
            return None
        path = root / (sha + '.apk')
        if path.is_symlink() or not path.is_file() or path.stat().st_size != size or size <= 0:
            return None
        return sha, size, version
    except (OSError, ValueError, KeyError, TypeError):
        return None


@router.get('/v1/app/releases/{digest}.apk')
def artifact(digest: str):
    if not re.fullmatch('[a-f0-9]{64}', digest):
        raise HTTPException(404, '安装包不存在')
    root = release_root()
    path = root / (digest + '.apk')
    if not path.is_file() or path.is_symlink():
        raise HTTPException(404, '安装包不存在')
    filename = 'ScreenMind-Android.apk'
    for channel, (_, edition, _) in EDITIONS.items():
        try:
            release = read_release(channel)
        except HTTPException as error:
            if error.status_code != 503:
                raise
            continue
        if release and release.sha256 == digest:
            version = release.version_name
            if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,39}', version):
                version = str(release.version_code)
            filename = f'ScreenMind-{version}-{edition}.apk'
            break
    if preview := sakura_preview():
        if preview[0] == digest:
            filename = f'ScreenMind-Sakura-{preview[2]}-arm64.apk'
    return FileResponse(path, media_type='application/vnd.android.package-archive', filename=filename,
        headers={'Cache-Control': 'public, max-age=31536000, immutable', 'X-Content-Type-Options': 'nosniff'})


@router.get('/download', response_class=HTMLResponse)
def download_page():
    releases = []
    unavailable = []
    for channel, (label, _, _) in EDITIONS.items():
        try:
            release = read_release(channel)
        except HTTPException as error:
            if error.status_code != 503:
                raise
            unavailable.append(label)
            continue
        if release:
            releases.append(release)
    shared_notes = len(releases) > 1 and len({(r.version_code, r.version_name, r.notes) for r in releases}) == 1
    blocks = []
    for release in releases:
        label, _, description = EDITIONS[release.channel]
        url = release.download_url or '/v1/app/releases/' + release.sha256 + '.apk'
        notes = f'<p class="notes">{escape(release.notes)}</p>' if release.notes and not shared_notes else ''
        blocks.append(
            f'<section class="card"><h2>{label}</h2><p>{description}</p>'
            f'<p class="metadata">{escape(release.version_name)} · {release.size_bytes / 1_000_000:.1f} MB</p>'
            f'{notes}<a class="download" href="{escape(url, quote=True)}" '
            f'aria-label="下载{label}安装包">下载安装包</a></section>'
        )
    body = ''.join(blocks)
    if preview := sakura_preview():
        sha, size, version = preview
        body += ('<section class="card"><h2>樱花粉预览版</h2>'
                 '<p>支持与内测版同时安装。适用于 ARM64 手机，需 Android 10 或更高版本。</p>'
                 f'<p class="metadata">{version} · {size / 1_000_000:.1f} MB</p>'
                 f'<a class="download" href="/v1/app/releases/{sha}.apk" '
                 'aria-label="下载樱花粉预览安装包">下载樱花粉预览</a></section>')
    if shared_notes and releases[0].notes:
        body += '<section class="changes"><h2>这版更新了什么</h2><p class="notes">' + escape(releases[0].notes) + '</p></section>'
    if unavailable:
        body += '<p class="card">' + '、'.join(unavailable) + '暂时无法下载，请稍后再试。</p>'
    if not body:
        body = '<p class="card">安装包准备好后会显示在这里。</p>'
    return HTMLResponse(
        _DOWNLOAD_HEADER + body + '<footer>安装时请按手机的系统提示确认。</footer></main></body></html>',
        status_code=503 if unavailable and not blocks else 200,
        headers={'Cache-Control': 'no-store', 'Content-Security-Policy': "default-src 'none'; img-src 'self'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'"},
    )


_DOWNLOAD_HEADER = '''<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>下载 ScreenMind</title>
  <link rel="icon" type="image/png" href="/plan-assets/screenmind.png">
  <style>
    :root {
      color-scheme: light;
      --canvas: #f5f7fb;
      --surface: #ffffff;
      --ink: #182943;
      --muted: #53657d;
      --accent: #2859c7;
      --accent-ink: #214ba9;
      --on-button: #fff;
      --top-glow: #e4edff;
      --line: #dbe4f1;
      --radius-panel: 22px;
      --radius-control: 14px;
    }
    @media (prefers-color-scheme: dark) {
      :root {
        color-scheme: dark;
        --canvas: #0e1727;
        --surface: #172437;
        --ink: #e8effb;
        --muted: #b7c5dc;
        --accent: #a9c6ff;
        --accent-ink: #cadbff;
        --on-button: #102b57;
        --line: #344761;
        --top-glow: #1d3354;
      }
    }
    *, *::before, *::after { box-sizing: border-box; }
    body {
      margin: 0;
      min-height: 100vh;
      color: var(--ink);
      background: linear-gradient(180deg, var(--top-glow), var(--canvas) 21rem);
      font: 1rem/1.65 "PingFang SC", "Microsoft YaHei UI", "Noto Sans CJK SC", sans-serif;
    }
    main { max-width: 49rem; margin: 0 auto; padding: clamp(2.2rem, 7vw, 4.5rem) clamp(1.25rem, 5vw, 2rem) 3rem; }
    header { display: flex; align-items: center; gap: clamp(.9rem, 3vw, 1.4rem); margin-bottom: clamp(1.4rem, 4vw, 2.25rem); }
    header img { width: clamp(3rem, 8vw, 4.25rem); height: auto; flex: none; }
    h1, h2, p { margin: 0; }
    h1 {
      font: 700 clamp(1.85rem, 5.5vw, 2.75rem)/1.18 ui-rounded, "PingFang SC", "Microsoft YaHei UI", sans-serif;
      letter-spacing: -.02em;
    }
    h2 { font-size: 1.2rem; line-height: 1.4; }
    header p { margin-top: .4rem; }
    header p, .metadata, footer { color: var(--muted); }
    .card {
      margin: 0 0 1rem;
      padding: clamp(1.25rem, 5vw, 1.75rem);
      border: 1px solid var(--line);
      border-radius: var(--radius-panel);
      background: var(--surface);
      box-shadow: 0 2px 8px rgba(45, 87, 168, .05), 0 12px 28px -20px rgba(19, 38, 68, .14);
      overflow-wrap: anywhere;
      transition: transform .18s ease, box-shadow .18s ease, border-color .18s ease;
    }
    .card:hover {
      border-color: rgba(45, 87, 168, .35);
      box-shadow: 0 4px 14px rgba(45, 87, 168, .08), 0 16px 32px -20px rgba(19, 38, 68, .18);
    }
    .metadata { margin-top: .3rem; font-size: .875rem; }
    .notes { margin-top: .85rem; white-space: pre-wrap; color: var(--ink); }
    .changes { margin: 1.5rem 0; overflow-wrap: anywhere; }
    .changes h2 { font-size: 1rem; }
    .changes .notes { margin-top: .35rem; color: var(--muted); }
    .download {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      min-height: 2.875rem;
      margin-top: 1.25rem;
      padding: .625rem 1.6rem;
      border-radius: var(--radius-control);
      background: var(--accent);
      color: var(--on-button);
      font-weight: 600;
      text-decoration: none;
      transition: background .16s ease, transform .14s ease;
    }
    .download:hover { background: var(--accent-ink); }
    .download:active { transform: scale(.98); }
    .download:focus-visible { outline: 3px solid var(--accent); outline-offset: 4px; }
    footer { margin-top: 1.5rem; font-size: .875rem; }
    @media (min-width: 38rem) {
      section.card { display: grid; grid-template-columns: minmax(0, 1fr) auto; column-gap: 1.5rem; }
      .card > h2, .card > p { grid-column: 1; }
      .card > .download { grid-column: 2; grid-row: 1 / 4; align-self: center; margin: 0; }
      .card > .notes { grid-column: 1 / -1; }
    }
    @media (max-width: 32rem) { header { align-items: flex-start; } .download { width: 100%; } }
    @media (max-width: 22rem) {
      header { gap: .7rem; }
      header img { width: 2.5rem; }
      h1 { font-size: 1.65rem; }
    }
    @media (prefers-reduced-motion: reduce) {
      .card, .download { transition: none; }
      .download:active { transform: none; }
    }
  </style>
</head>
<body>
<main>
  <header>
    <img src="/plan-assets/screenmind.png" width="68" height="68" alt="">
    <div><h1>下载 ScreenMind</h1>
    <p>安卓安装包</p></div>
  </header>
'''
