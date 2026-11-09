param([int]$Port = 8765, [string]$Bind = '127.0.0.1')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Push-Location $projectRoot
try {
    if (-not (Test-Path '.venv/Scripts/python.exe')) {
        python -m venv .venv
    }
    'import importlib.util, sys; sys.exit(0 if importlib.util.find_spec("pip") else 1)' | & '.venv/Scripts/python.exe' -
    if ($LASTEXITCODE -ne 0) {
        & '.venv/Scripts/python.exe' -m ensurepip --upgrade
        if ($LASTEXITCODE -ne 0) { throw 'pip setup failed' }
    }
    @'
from importlib.metadata import version, PackageNotFoundError
import sys
expected = {'fastapi':'0.136.1', 'pydantic':'2.13.4', 'uvicorn':'0.46.0', 'starlette':'1.10.0', 'httpx':'0.28.1', 'argon2-cffi':'25.1.0'}
try:
    sys.exit(0 if all(version(k) == v for k,v in expected.items()) else 1)
except PackageNotFoundError:
    sys.exit(1)
'@ | & '.venv/Scripts/python.exe' -
    if ($LASTEXITCODE -ne 0) {
        & '.venv/Scripts/python.exe' -m pip install -r backend/requirements.lock.txt
        if ($LASTEXITCODE -ne 0) { throw 'Backend dependency installation failed' }
    }
    $cloudProject = Join-Path (Split-Path $projectRoot -Parent) 'Cloud Agent'
    if (-not (Test-Path (Join-Path $cloudProject 'pyproject.toml'))) { throw 'Cloud Agent is required. Check out the complete ScreenMind repository.' }
    @'
import importlib.util, pathlib, sys
spec = importlib.util.find_spec('screenmind')
expected = pathlib.Path(sys.argv[1]).resolve() / 'screenmind' / '__init__.py'
sys.exit(0 if spec and spec.origin and pathlib.Path(spec.origin).resolve() == expected else 1)
'@ | & '.venv/Scripts/python.exe' - $cloudProject
    if ($LASTEXITCODE -ne 0) {
        & '.venv/Scripts/python.exe' -m pip install --no-deps -e $cloudProject
        if ($LASTEXITCODE -ne 0) { throw 'Cloud Agent installation failed' }
    }
    & '.venv/Scripts/python.exe' -m uvicorn backend.main:app --host $Bind --port $Port --no-proxy-headers
} finally { Pop-Location }

