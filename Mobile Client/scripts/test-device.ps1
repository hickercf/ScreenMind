param([string]$Serial = 'emulator-5554', [switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Push-Location $projectRoot
try {
    if (-not $SkipBuild) { & "$PSScriptRoot/build.ps1" -Tasks @('assembleDebug','assembleDebugAndroidTest') }
    $adb = Join-Path $projectRoot '.tools/sdk/platform-tools/adb.exe'
    & $adb -s $Serial reverse tcp:8765 tcp:8765
    & $adb -s $Serial install -r 'app/build/outputs/apk/debug/app-debug.apk'
    if ($LASTEXITCODE -ne 0) { throw 'App installation failed' }
    & $adb -s $Serial install -r 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
    if ($LASTEXITCODE -ne 0) { throw 'Test installation failed' }
    New-Item -ItemType Directory -Path 'docs/previews' -Force | Out-Null
    $result = & $adb -s $Serial shell am instrument -w -r 'cn.weiguang.app.test/androidx.test.runner.AndroidJUnitRunner'
    $result | Out-File -Encoding utf8 'docs/device-test-output.txt'
    $result | Select-Object -Last 12
    & $adb -s $Serial pull '/sdcard/Android/data/cn.weiguang.app/files/screenshots/.' 'docs/previews'
    if (-not (($result -join "`n") -match 'OK \(4 tests\)')) { throw 'Device tests failed. See docs/device-test-output.txt' }
} finally { Pop-Location }
