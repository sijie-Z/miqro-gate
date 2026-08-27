#!/bin/sh
# Check observations from the Compatibility Mock Server.
# Prints the allowlisted diagnostic JSON from GET /observations.
# Default Mock URL: http://127.0.0.1:8082

MOCK_URL="${1:-http://127.0.0.1:8082}"
URI="${MOCK_URL}/observations"

echo "GET $URI" >&2
curl -s -X GET "$URI" -H "Accept: application/json" || {
    echo "ERROR: Failed to reach Mock at $URI" >&2
    exit 1
}
