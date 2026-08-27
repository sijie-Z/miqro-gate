# Clear all observations from the Compatibility Mock Server.
# Sends DELETE /observations to clear the store.
# Default Mock URL: http://127.0.0.1:8082

param(
    [string]$MockUrl = "http://127.0.0.1:8082"
)

$ErrorActionPreference = "Stop"

$Uri = "${MockUrl}/observations"
Write-Host "DELETE $Uri" -ForegroundColor Gray

try {
    $response = Invoke-WebRequest -Uri $Uri -Method DELETE
    Write-Host $response.Content
} catch {
    Write-Error "Failed to reach Mock at $Uri : $_"
    exit 1
}
