#!/bin/sh
# Compatibility Mock Server — POSIX shell launcher
# Starts the standalone compatibility mock on loopback port 8082.
# Foreground process; Ctrl+C to stop.

set -e

# Resolve repo root from script location (scripts/cc-switch-compatibility/ -> repo root)
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# Respect caller environment
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

JAR_FILE="$REPO_ROOT/backend/test-support/target/test-support-0.1.0-SNAPSHOT-compatibility.jar"

if [ "$SKIP_BUILD" != "true" ]; then
    echo "Building test-support compatibility jar..."
    cd "$REPO_ROOT"
    ./mvnw -pl test-support -am package -DskipTests --batch-mode --no-transfer-progress
    echo "Build complete."
else
    echo "Skipping build (MIQROKEY_SKIP_BUILD=true). Using: $JAR_FILE"
    if [ ! -f "$JAR_FILE" ]; then
        echo "ERROR: Jar not found at $JAR_FILE. Remove MIQROKEY_SKIP_BUILD or build first." >&2
        exit 1
    fi
fi

MOCK_PORT="${COMPATIBILITY_MOCK_PORT:-8082}"

echo ""
echo "====================================================="
echo " Starting Compatibility Mock Server"
echo "====================================================="
echo "  Bind address:   127.0.0.1:$MOCK_PORT"
echo "  Health URL:     http://127.0.0.1:${MOCK_PORT}/health"
echo "  Observations:   http://127.0.0.1:${MOCK_PORT}/observations"
echo "  Clear obs:      http://127.0.0.1:${MOCK_PORT}/observations (DELETE)"
echo "  Press Ctrl+C to stop the Mock and release the port."
echo "====================================================="
echo ""

exec java "-Dcompatibility.mock.port=$MOCK_PORT" -jar "$JAR_FILE"
