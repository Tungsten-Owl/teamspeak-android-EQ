# Compiles the platform-independent protocol/session layers with a desktop JDK
# and runs the end-to-end self-test against a local fake gateway.
#
# Usage:  powershell -ExecutionPolicy Bypass -File android-native\tools\verify-core.ps1
# Requires JDK 17+ (JAVA_HOME or javac on PATH). No Android SDK is needed.
# This script is intentionally ASCII-only: Windows PowerShell 5.1 reads BOM-less
# scripts in the ANSI code page, which would corrupt non-ASCII source text.

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root "build\core-test"

function Resolve-JdkTool([string]$name) {
    if ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME "bin\$name"
        if (Test-Path $candidate) { return $candidate }
    }
    $command = Get-Command $name -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    throw "Cannot find $name; set JAVA_HOME or add a JDK to PATH."
}

$javac = Resolve-JdkTool "javac"
$java = Resolve-JdkTool "java"

Remove-Item -Recurse -Force $out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $out | Out-Null

$base = Join-Path $root "app\src\main\java\com\webspeak\nativeclient"
$sources = @()
foreach ($package in @("core", "net", "voice")) {
    $sources += Get-ChildItem (Join-Path $base $package) -Filter *.java -Recurse | ForEach-Object { $_.FullName }
}
$sources += Get-ChildItem (Join-Path $root "tools\selftest") -Filter *.java | ForEach-Object { $_.FullName }

Write-Host "Compiling $($sources.Count) source files..."
& $javac -encoding UTF-8 --release 17 -d $out @sources
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "Running protocol self-test..."
& $java "-Dfile.encoding=UTF-8" -cp $out selftest.SelfTest
exit $LASTEXITCODE
