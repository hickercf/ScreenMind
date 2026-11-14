param([switch]$SetupSdk, [string[]]$Tasks = @('assembleDebug','testDebugUnitTest','lintDebug'))
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$env:JAVA_HOME = (Get-Content -LiteralPath (Join-Path $projectRoot '.tools/java-home.txt') -Raw).Trim()
$sourceRoot = $projectRoot
$ownedDrive = $null
# JDK 17 test workers on Windows cannot reliably decode Chinese classpaths.
# Use a temporary ASCII alias; source files stay in the original directory.
if ($projectRoot -match '[^\x00-\x7F]') {
    $used = @([IO.DriveInfo]::GetDrives() | ForEach-Object { $_.Name.Substring(0,1) })
    $letter = @('Z','Y','X','W','V','U','T','S','R') | Where-Object { $_ -notin $used } | Select-Object -First 1
    if (-not $letter) { throw 'No free drive letter for the temporary build alias. Use an ASCII checkout path.' }
    & "$env:SystemRoot/System32/subst.exe" "${letter}:" (Split-Path $projectRoot -Parent)
    if ($LASTEXITCODE -ne 0) { throw 'Could not create a temporary build alias. Use an ASCII checkout path.' }
    $ownedDrive = "${letter}:"
    $projectRoot = Join-Path "${letter}:\" (Split-Path $sourceRoot -Leaf)
}
try {
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
$buildOptions = @()
# Java does not automatically use the shell's HTTP proxy settings.
if ($env:HTTPS_PROXY) {
    $buildProxy = [uri]$env:HTTPS_PROXY
    if ($buildProxy.Scheme -eq 'http' -and -not $buildProxy.UserInfo) {
        $buildOptions += @("-Dhttps.proxyHost=$($buildProxy.Host)", "-Dhttps.proxyPort=$($buildProxy.Port)", "-Dhttp.proxyHost=$($buildProxy.Host)", "-Dhttp.proxyPort=$($buildProxy.Port)")
    }
}
Push-Location $projectRoot
try {
    & $gradle --no-daemon --console=plain @buildOptions @Tasks
    if ($LASTEXITCODE -ne 0) { throw 'Android build failed' }
} finally { Pop-Location }
} finally {
    if ($ownedDrive) {
        $env:ANDROID_HOME = Join-Path $sourceRoot '.tools/sdk'
        $restoredSdk = $env:ANDROID_HOME.Replace('\','/')
        [IO.File]::WriteAllText((Join-Path $sourceRoot 'local.properties'), "sdk.dir=$restoredSdk`n")
        & "$env:SystemRoot/System32/subst.exe" $ownedDrive /D
    }
}

