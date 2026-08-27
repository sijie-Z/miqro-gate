#!/bin/sh
# Clear all observations from the Compatibility Mock Server.
# Sends DELETE /observations to clear the store.
# Default Mock URL: http://127.0.0.1:8082

MOCK_URL="${1:-http://127.0.0.1:8082}"
URI="${MOCK_URL}/observations"

echo "DELETE $URI" >&2
curl -s -X DELETE "$URI" || {
    echo "ERROR: Failed to reach Mock at $URI" >&2
    exit 1
}
