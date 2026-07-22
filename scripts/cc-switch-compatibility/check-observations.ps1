# Check observations from the Compatibility Mock Server.
# Prints the allowlisted diagnostic JSON from GET /observations.
# Default Mock URL: http://127.0.0.1:8082

param(
    [string]$MockUrl = "http://127.0.0.1:8082"
)

$ErrorActionPreference = "Stop"

$Uri = "${MockUrl}/observations"
Write-Host "GET $Uri" -ForegroundColor Gray

try {
    $response = Invoke-WebRequest -Uri $Uri -Method GET -ContentType "application/json"
    if ($response.Content) {
        # Pretty-print if jq-like formatting; otherwise raw
        Write-Host $response.Content
    } else {
        Write-Host "[]" -ForegroundColor Gray
    }
} catch {
    Write-Error "Failed to reach Mock at $Uri : $_"
    exit 1
}
