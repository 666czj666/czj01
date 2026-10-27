# tools/env.ps1
# Usage (note the leading dot and space):  . .\tools\env.ps1
#
# IMPORTANT: This file is intentionally 100% ASCII.
#   Console code page on this machine is 936 (GBK) and PowerShell is 5.1,
#   which reads BOM-less .ps1 files as ANSI/GBK. Any non-ASCII literal here
#   would be corrupted. All paths are derived at runtime instead.
#
# Also: PowerShell 5.1 has no && / ternary / ?? operators.

$root = Split-Path -Parent $PSScriptRoot

$env:JAVA_HOME        = Join-Path $root '.toolchain\jdk-17'
$env:ANDROID_HOME     = Join-Path $root '.toolchain\sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:GRADLE_USER_HOME = Join-Path $root '.gradle-home'

# sdkmanager writes to the user home by default; keep it inside the workspace.
$env:ANDROID_USER_HOME = Join-Path $root '.gradle-home\android-user'

# Derive the bundled Node path from DSH_HOME (avoids hardcoding a non-ASCII path).
if ($env:DSH_HOME) {
    $env:ZILV_NODE = Join-Path $env:DSH_HOME 'dsh-runtimes\dsh-primary-runtime\dependencies\node\bin\node.exe'
} else {
    $env:ZILV_NODE = ''
}

$gradleBin = Join-Path $root '.toolchain\gradle-8.11.1\bin'
$paths = @(
    (Join-Path $env:JAVA_HOME 'bin'),
    $gradleBin,
    (Join-Path $env:ANDROID_HOME 'platform-tools'),
    (Join-Path $env:ANDROID_HOME 'cmdline-tools\latest\bin')
)
$env:PATH = ($paths -join ';') + ';' + $env:PATH

$env:ZILV_ROOT = $root

Write-Host '--- zilv dev environment ---' -ForegroundColor Cyan
Write-Host "ZILV_ROOT         = $root"
Write-Host "JAVA_HOME         = $env:JAVA_HOME"
Write-Host "ANDROID_HOME      = $env:ANDROID_HOME"
Write-Host "GRADLE_USER_HOME  = $env:GRADLE_USER_HOME"
Write-Host "ZILV_NODE         = $env:ZILV_NODE"
Write-Host ''

function Test-Tool($name) {
    $c = Get-Command $name -ErrorAction SilentlyContinue
    if ($c) {
        Write-Host ("  [OK]     {0,-12} {1}" -f $name, $c.Source) -ForegroundColor Green
    } else {
        Write-Host ("  [MISSING] {0}" -f $name) -ForegroundColor DarkGray
    }
}

Write-Host 'Toolchain:'
Test-Tool 'java'
Test-Tool 'javac'
Test-Tool 'gradle'
Test-Tool 'sdkmanager'
Test-Tool 'adb'
Write-Host ''
