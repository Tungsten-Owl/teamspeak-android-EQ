# Builds the debug APKs and copies them into dist/ with the version from build.gradle.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\package-apk.ps1
#
# JAVA_HOME and ANDROID_HOME are auto-detected from the usual Android Studio
# install when they are not already set.
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$app = Join-Path $root 'app'

if (-not $env:JAVA_HOME) {
    foreach ($candidate in @(
            'C:\Program Files\Android\Android Studio\jbr',
            'C:\Program Files\Android\Android Studio\jre')) {
        if (Test-Path (Join-Path $candidate 'bin\java.exe')) {
            $env:JAVA_HOME = $candidate
            break
        }
    }
}
if (-not $env:JAVA_HOME) {
    throw 'JAVA_HOME is not set and the Android Studio JBR was not found.'
}

if (-not $env:ANDROID_HOME) {
    # Search order: an existing local.properties (what Android Studio writes), the
    # environment variables Android tooling already understands, then the SDK
    # layouts this project has been built with before.
    $candidates = New-Object System.Collections.Generic.List[string]
    foreach ($value in @($env:ANDROID_SDK_ROOT)) {
        if ($value) { $candidates.Add($value) }
    }
    $localProps = Join-Path $root 'local.properties'
    if (Test-Path $localProps) {
        $line = Select-String -Path $localProps -Pattern '^\s*sdk\.dir\s*=\s*(.+)$' | Select-Object -First 1
        if ($line) { $candidates.Add(($line.Matches[0].Groups[1].Value.Trim() -replace '\\\\', '\')) }
    }
    $candidates.Add((Join-Path $env:LOCALAPPDATA 'Android\Sdk'))
    foreach ($drive in @('C:', 'D:', 'E:', 'F:')) {
        foreach ($leaf in @('Android\Sdk', 'Android\sdk', 'AndroidSdk', 'Sdk')) {
            # Plain concatenation, not Join-Path: Join-Path throws for a drive
            # that does not exist on this machine.
            $candidates.Add("$drive\$leaf")
        }
    }

    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path (Join-Path $candidate 'platform-tools'))) {
            $env:ANDROID_HOME = $candidate
            break
        }
    }
    if (-not $env:ANDROID_HOME) {
        foreach ($candidate in $candidates) {
            if ($candidate -and (Test-Path (Join-Path $candidate 'cmdline-tools'))) {
                $env:ANDROID_HOME = $candidate
                break
            }
        }
    }
}
if (-not $env:ANDROID_HOME) {
    Write-Host 'Android SDK not found. Checked:'
    Write-Host '  ANDROID_HOME / ANDROID_SDK_ROOT'
    Write-Host "  $(Join-Path $root 'local.properties')  (sdk.dir=...)"
    Write-Host "  $(Join-Path $env:LOCALAPPDATA 'Android\Sdk')"
    Write-Host '  C:\Android\Sdk, C:\Android\sdk, D:\Android\Sdk, ...'
    Write-Host ''
    Write-Host 'Fix either way, then run this script again:'
    Write-Host '  setx ANDROID_HOME "C:\path\to\Android\Sdk"'
    Write-Host '  -- or --'
    Write-Host "  create $(Join-Path $root 'local.properties') containing:"
    Write-Host '  sdk.dir=C\:\\path\\to\\Android\\Sdk'
    throw 'Android SDK not found.'
}

$gradle = Join-Path $root 'gradlew.bat'
if (-not (Test-Path $gradle)) { throw "Missing Gradle wrapper: $gradle" }

# dist/ must only ever hold packages produced by the current source, so the
# previous set is removed before the build starts. Only *.apk is touched.
$dist = Join-Path $root 'dist'
if (Test-Path $dist) {
    foreach ($stale in @(Get-ChildItem -Path $dist -Filter '*.apk' -File)) {
        Remove-Item -Path $stale.FullName -Force
        Write-Host "removed      = $($stale.Name)"
    }
}

Write-Host "JAVA_HOME    = $env:JAVA_HOME"
Write-Host "ANDROID_HOME = $env:ANDROID_HOME"
Write-Host ''

Push-Location $root
try {
    & $gradle ':app:assembleDebug'
    if ($LASTEXITCODE -ne 0) { throw "gradle exited with code $LASTEXITCODE" }
} finally {
    Pop-Location
}

$debugDir = Join-Path $app 'build\outputs\apk\debug'
$apks = @()
if (Test-Path $debugDir) {
    $apks = @(Get-ChildItem -Path $debugDir -Filter '*.apk' -File)
}
if ($apks.Count -eq 0) { throw "No APK was produced in $debugDir" }

# Gradle bumps version.properties while it configures the build, so read it after
# the build: this is the version the APK was actually stamped with.
$version = 'dev'
$versionFile = Join-Path $root 'version.properties'
if (Test-Path $versionFile) {
    $line = Select-String -Path $versionFile -Pattern '^build=(\d+)$' | Select-Object -First 1
    if ($line) { $version = '1.5.' + $line.Matches[0].Groups[1].Value }
}
Write-Host "version      = $version"

$dist = Join-Path $root 'dist'
New-Item -ItemType Directory -Force -Path $dist | Out-Null

foreach ($apk in $apks) {
    $abi = ''
    if ($apk.Name -match '-(arm64-v8a|armeabi-v7a|x86_64|x86)-debug') { $abi = '-' + $Matches[1] }
    $target = Join-Path $dist ("TS3Mobile-$version$abi-debug.apk")
    Copy-Item -Path $apk.FullName -Destination $target -Force
    $sizeMb = [math]::Round($apk.Length / 1MB, 1)
    Write-Host ("  $target  ($sizeMb MB)")
}

Write-Host ''
Write-Host "Done. $($apks.Count) APK(s) in $dist"
