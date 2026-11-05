# Runs the direct TS3 core integration probe. Start a TeamSpeak 3 server on
# 127.0.0.1:9987 first; the probe creates two clients and checks the welcome
# directory, channel chat and Opus voice loopback.
$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Tools\jdk-21' }
$env:ANDROID_HOME = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { 'C:\Android\sdk' }
Set-Location (Split-Path -Parent $PSScriptRoot)
& .\gradlew.bat --no-daemon --console=plain :core:coreProbe
if ($LASTEXITCODE -ne 0) { throw "core probe failed with exit code $LASTEXITCODE" }
