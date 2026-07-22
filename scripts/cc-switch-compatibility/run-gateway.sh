#!/bin/sh
# MiQroKey Gateway — POSIX shell launcher
# Builds and runs gateway-app on port 8081, proxying to the local
# Compatibility Mock Server on port 8082.
# Foreground process; Ctrl+C to stop.
#
# Pre-requisite: start run-mock.sh in a separate terminal first.

set -e

# Resolve repo root from script location
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

SKIP_BUILD="${MIQROKEY_SKIP_BUILD:-false}"

# Require Java 21
JAVA_VERSION="$(java -version 2>&1 | head -1 || true)"
echo "Java: $JAVA_VERSION"
JAVA_MAJOR="$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\)\..*/\1/p')"
if [ -z "$JAVA_MAJOR" ]; then
    echo "ERROR: Could not detect Java version. Java 21 is required." >&2
    exit 1
fi
if [ "$JAVA_MAJOR" != "21" ]; then
    echo "WARNING: Expected Java 21; detected major $JAVA_MAJOR. Proceed with caution." >&2
fi

JAR_FILE="$REPO_ROOT/backend/gateway-app/target/gateway-app-0.1.0-SNAPSHOT.jar"

if [ "$SKIP_BUILD" != "true" ]; then
    echo "Building gateway-app jar..."
    cd "$REPO_ROOT"
    ./mvnw -pl gateway-app -am package -DskipTests --batch-mode --no-transfer-progress
    echo "Build complete."
else
    echo "Skipping build (MIQROKEY_SKIP_BUILD=true). Using: $JAR_FILE"
    if [ ! -f "$JAR_FILE" ]; then
        echo "ERROR: Jar not found at $JAR_FILE. Remove MIQROKEY_SKIP_BUILD or build first." >&2
        exit 1
    fi
fi

# Use loopback, non-secret config only — no credential in process arguments
export MIQROKEY_GATEWAY_PORT=8081
export MIQROKEY_UPSTREAM_URL="http://127.0.0.1:8082"
export MIQROKEY_UPSTREAM_CONNECT_TIMEOUT="PT5S"
export MIQROKEY_UPSTREAM_RESPONSE_TIMEOUT="PT10M"

echo ""
echo "====================================================="
echo " Starting MiQroKey Gateway"
echo "====================================================="
echo "  Gateway port:    8081"
echo "  Upstream URL:    http://127.0.0.1:8082 (Mock)"
echo "  Health check:    http://127.0.0.1:8081/actuator/health"
echo "  Supported paths: POST /v1/messages"
echo "                   POST /v1/chat/completions"
echo "                   POST /v1/responses"
echo "  Press Ctrl+C to stop the Gateway."
echo "====================================================="
echo ""

exec java -jar "$JAR_FILE"
