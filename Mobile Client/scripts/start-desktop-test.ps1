param([int]$EmulatorPort = 5556)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$sdk = Join-Path $projectRoot '.tools/sdk'
$adb = Join-Path $sdk 'platform-tools/adb.exe'
$emulator = Join-Path $sdk 'emulator/emulator.exe'
$apk = Join-Path $projectRoot 'dist/weiguang-0.1.0-universal-debug.apk'
$avdName = 'WeiguangTest'
$serial = "emulator-$EmulatorPort"
foreach ($required in @($adb, $emulator, $apk, (Join-Path $projectRoot '.tools/avd/WeiguangTest.ini'))) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Missing local test component: $required. See README.md." }
}
if ($EmulatorPort -lt 5554 -or $EmulatorPort -gt 5682 -or $EmulatorPort % 2 -ne 0) { throw 'Use an even emulator port from 5554 to 5682.' }
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
$env:ANDROID_AVD_HOME = Join-Path $projectRoot '.tools/avd'
$logDir = Join-Path $projectRoot '.tools/logs'
New-Item -ItemType Directory -Path $logDir -Force | Out-Null

function Get-BackendHealth {
    try { Invoke-RestMethod 'http://127.0.0.1:8765/health' -TimeoutSec 2 } catch { $null }
}
$health = Get-BackendHealth
if (-not $health) {
    Write-Host 'Starting the local backend...'
    $backendScript = Join-Path $PSScriptRoot 'start-backend.ps1'
    Start-Process -FilePath 'powershell.exe' -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File',('"' + $backendScript + '"')) -WorkingDirectory $projectRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logDir 'desktop-backend.out.log') -RedirectStandardError (Join-Path $logDir 'desktop-backend.err.log') | Out-Null
    $deadline = (Get-Date).AddMinutes(3)
    do {
        Start-Sleep -Seconds 2
        $health = Get-BackendHealth
        if ((Get-Date) -gt $deadline) { throw "Backend did not start. Check $logDir/desktop-backend.err.log" }
    } until ($health)
}
if ($health.status -ne 'ok' -or $health.schema_version -ne 1 -or -not $health.decision_provider) { throw 'Port 8765 is occupied by another service.' }

# Only replace the hidden emulator created by this project's automated tests.
$hidden = Get-CimInstance Win32_Process | Where-Object {
    $_.Name -eq 'qemu-system-x86_64-headless.exe' -and
    $_.ExecutablePath -like "$sdk\emulator\*" -and
    $_.CommandLine -match '-avd\s+WeiguangTest(?:\s|$)' -and
    $_.CommandLine -match '-no-window'
}
$hidden | ForEach-Object { Stop-Process -Id $_.ProcessId }
if ($hidden) { Start-Sleep -Seconds 2 }
$devices = & $adb devices
if ($devices -match "^$serial\s+device") {
    $runningName = (& $adb -s $serial emu avd name) -join ' '
    if ($runningName -notmatch 'WeiguangTest') { throw "Another emulator uses $serial. Run this script with -EmulatorPort 5558." }
} else {
    Write-Host 'Opening the Android emulator window...'
    Start-Process -FilePath $emulator -ArgumentList @('-avd',$avdName,'-port',"$EmulatorPort",'-no-audio','-no-boot-anim','-no-snapshot','-gpu','swiftshader','-memory','2048') -WorkingDirectory $projectRoot -RedirectStandardOutput (Join-Path $logDir 'desktop-emulator.out.log') -RedirectStandardError (Join-Path $logDir 'desktop-emulator.err.log') | Out-Null
}
$deadline = (Get-Date).AddMinutes(4)
do {
    Start-Sleep -Seconds 2
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $boot = (& $adb -s $serial shell getprop sys.boot_completed 2>$null) -join ''
    $ErrorActionPreference = $previousPreference
    if ((Get-Date) -gt $deadline) { throw "Emulator did not boot. Check $logDir/desktop-emulator.err.log" }
} until ($boot.Trim() -eq '1')
& $adb -s $serial shell wm size 640x1280 | Out-Null
& $adb -s $serial shell wm density 240 | Out-Null
& $adb -s $serial reverse tcp:8765 tcp:8765
if ($LASTEXITCODE -ne 0) { throw 'Backend forwarding failed.' }
Write-Host 'Installing and opening Weiguang...'
& $adb -s $serial install -r $apk
if ($LASTEXITCODE -ne 0) { throw 'App installation failed.' }
& $adb -s $serial shell am start -n cn.weiguang.app/.MainActivity
if ($LASTEXITCODE -ne 0) { throw 'App launch failed.' }
Write-Host 'Ready. Use the emulator window. Backend: http://127.0.0.1:8765'
