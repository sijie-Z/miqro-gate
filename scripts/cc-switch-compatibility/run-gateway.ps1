# MiQroKey Gateway — Windows PowerShell launcher
# Builds and runs gateway-app on port 8081, proxying to the local
# Compatibility Mock Server on port 8082.
# Foreground process; Ctrl+C to stop.
#
# Pre-requisite: start run-mock.ps1 in a separate terminal first.

param(
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"

# Resolve repo root from script location
$RepoRoot = Resolve-Path "$PSScriptRoot\..\.."

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

$JarFile = "$RepoRoot\backend\gateway-app\target\gateway-app-0.1.0-SNAPSHOT.jar"

if (-not $SkipBuild) {
    Write-Host "Building gateway-app jar..." -ForegroundColor Cyan
    Push-Location $RepoRoot
    try {
        & .\mvnw.cmd -pl gateway-app -am package -DskipTests --batch-mode --no-transfer-progress
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

# Use loopback, non-secret config only — no credential in process arguments
$env:MIQROKEY_GATEWAY_PORT = "8081"
$env:MIQROKEY_UPSTREAM_URL = "http://127.0.0.1:8082"
$env:MIQROKEY_UPSTREAM_CONNECT_TIMEOUT = "PT5S"
$env:MIQROKEY_UPSTREAM_RESPONSE_TIMEOUT = "PT10M"

Write-Host ""
Write-Host "=====================================================" -ForegroundColor Cyan
Write-Host " Starting MiQroKey Gateway" -ForegroundColor Cyan
Write-Host "=====================================================" -ForegroundColor Cyan
Write-Host "  Gateway port:    8081" -ForegroundColor White
Write-Host "  Upstream URL:    http://127.0.0.1:8082 (Mock)" -ForegroundColor White
Write-Host "  Health check:    http://127.0.0.1:8081/actuator/health" -ForegroundColor White
Write-Host "  Supported paths: POST /v1/messages" -ForegroundColor White
Write-Host "                   POST /v1/chat/completions" -ForegroundColor White
Write-Host "                   POST /v1/responses" -ForegroundColor White
Write-Host "  Press Ctrl+C to stop the Gateway." -ForegroundColor Yellow
Write-Host "=====================================================" -ForegroundColor Cyan
Write-Host ""

& java -jar $JarFile
