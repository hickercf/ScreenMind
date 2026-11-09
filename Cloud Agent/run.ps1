param([int]$Port = 8765)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$env:PYTHONIOENCODING = 'utf-8'
$taskPython = Join-Path $PSScriptRoot '.venv\Scripts\python.exe'
if (-not (Test-Path -LiteralPath $taskPython)) { $taskPython = 'python' }
& $taskPython -m uvicorn screenmind.api:create_app --factory --host 127.0.0.1 --port $Port --no-proxy-headers
exit $LASTEXITCODE
