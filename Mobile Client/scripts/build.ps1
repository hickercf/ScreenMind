param([switch]$SetupSdk, [string[]]$Tasks = @('assembleDebug','testDebugUnitTest','lintDebug'))
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$env:JAVA_HOME = (Get-Content -LiteralPath (Join-Path $projectRoot '.tools/java-home.txt') -Raw).Trim()
$env:ANDROID_HOME = Join-Path $projectRoot '.tools/sdk'
$env:Path = "$env:JAVA_HOME/bin;$env:Path"
if ($SetupSdk) {
    $manager = Join-Path $env:ANDROID_HOME 'cmdline-tools/latest/bin/sdkmanager.bat'
    1..30 | ForEach-Object { 'y' } | & $manager "--sdk_root=$env:ANDROID_HOME" --licenses
    if ($LASTEXITCODE -ne 0) { throw 'SDK license setup failed' }
    & $manager "--sdk_root=$env:ANDROID_HOME" 'platform-tools' 'platforms;android-35' 'build-tools;35.0.0'
    if ($LASTEXITCODE -ne 0) { throw 'SDK installation failed' }
}
$sdkPath = $env:ANDROID_HOME.Replace('\','/')
[IO.File]::WriteAllText((Join-Path $projectRoot 'local.properties'), "sdk.dir=$sdkPath`n")
$gradle = Join-Path $projectRoot '.tools/gradle/gradle-8.11.1/bin/gradle.bat'
Push-Location $projectRoot
try {
    & $gradle --no-daemon --console=plain @Tasks
    if ($LASTEXITCODE -ne 0) { throw 'Android build failed' }
} finally { Pop-Location }
