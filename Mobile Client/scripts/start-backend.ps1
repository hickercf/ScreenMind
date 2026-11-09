param([int]$Port = 8765, [string]$Bind = '127.0.0.1')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Push-Location $projectRoot
try {
    if (-not (Test-Path '.venv/Scripts/python.exe')) {
        python -m venv .venv
    }
    & '.venv/Scripts/python.exe' -c 'import fastapi, uvicorn, argon2, httpx' 2>$null
    if ($LASTEXITCODE -ne 0) {
        & '.venv/Scripts/python.exe' -m pip install -r backend/requirements.lock.txt
        if ($LASTEXITCODE -ne 0) { throw 'Backend dependency installation failed' }
    }
    & '.venv/Scripts/python.exe' -m uvicorn backend.main:app --host $Bind --port $Port
} finally { Pop-Location }
