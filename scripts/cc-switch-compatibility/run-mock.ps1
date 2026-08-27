# Compatibility Mock Server — Windows PowerShell launcher
# Starts the standalone compatibility mock on loopback port 8082.
# Foreground process; Ctrl+C to stop.

param(
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"

# Resolve repo root from script location (scripts/cc-switch-compatibility/ -> repo root)
$RepoRoot = Resolve-Path "$PSScriptRoot\..\.."

# Respect caller environment: process-local assignment only
if ($env:MIQROKEY_SKIP_BUILD -eq "true") { $SkipBuild = $true }

# Require Java 21
$javaVersion = java -version 2>&1 | Select-String "version" | ForEach-Object { $_.Line }
Write-Host "Java: $javaVersion" -ForegroundColor Gray
$javaVersionFull = java -version 2>&1 | Select-String 'version "(\d+[^"]*)"' | ForEach-Object { $_.Matches.Groups[1].Value }
if (-not $javaVersionFull) {
    Write-Error "Could not detect Java version. Java 21 is required."
    exit 1
}
if (-not ($javaVersionFull -match '^21\.')) {
    Write-Warning "Expected Java 21; detected $javaVersionFull. Proceed with caution."
}

$JarFile = "$RepoRoot\backend\test-support\target\test-support-0.1.0-SNAPSHOT-compatibility.jar"

if (-not $SkipBuild) {
    Write-Host "Building test-support compatibility jar..." -ForegroundColor Cyan
    Push-Location $RepoRoot
    try {
        & .\mvnw.cmd -pl test-support -am package -DskipTests --batch-mode --no-transfer-progress
        if ($LASTEXITCODE -ne 0) { throw "Maven build failed with exit code $LASTEXITCODE" }
    } finally {
        Pop-Location
    }
    Write-Host "Build complete." -ForegroundColor Green
} else {
    Write-Host "Skipping build (SkipBuild). Using: $JarFile" -ForegroundColor Yellow
    if (-not (Test-Path $JarFile)) {
        Write-Error "Jar not found at $JarFile. Remove SkipBuild or build first."
        exit 1
    }
}

$MockPort = if ($env:COMPATIBILITY_MOCK_PORT) { $env:COMPATIBILITY_MOCK_PORT } else { "8082" }

Write-Host ""
Write-Host "=====================================================" -ForegroundColor Cyan
Write-Host " Starting Compatibility Mock Server" -ForegroundColor Cyan
Write-Host "=====================================================" -ForegroundColor Cyan
Write-Host "  Bind address:   127.0.0.1:$MockPort" -ForegroundColor White
Write-Host "  Health URL:     http://127.0.0.1:${MockPort}/health" -ForegroundColor White
Write-Host "  Observations:   http://127.0.0.1:${MockPort}/observations" -ForegroundColor White
Write-Host "  Clear obs:      http://127.0.0.1:${MockPort}/observations (DELETE)" -ForegroundColor White
Write-Host "  Press Ctrl+C to stop the Mock and release the port." -ForegroundColor Yellow
Write-Host "=====================================================" -ForegroundColor Cyan
Write-Host ""

& java "-Dcompatibility.mock.port=$MockPort" -jar $JarFile
