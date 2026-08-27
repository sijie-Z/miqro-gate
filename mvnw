#!/bin/sh
# ----------------------------------------------------------------------------
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#    https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
# ----------------------------------------------------------------------------

# Maven Wrapper launcher for MiQroKey Gateway.
# The POM lives in backend/; the wrapper runs from the repo root.

set -e

# Resolve symlinks and find the project root
PRG="$0"
while [ -h "$PRG" ]; do
  ls=$(ls -ld "$PRG")
  link=$(expr "$ls" : '.*-> \(.*\)$')
  if expr "$link" : '/.*' > /dev/null; then
    PRG="$link"
  else
    PRG=$(dirname "$PRG")/"$link"
  fi
done
SAVED="$(pwd)"
cd "$(dirname "$PRG")/" >/dev/null
APP_HOME="$(pwd -P)"
cd "$SAVED" >/dev/null

# Maven project base directory (where the POM lives)
MAVEN_PROJECTBASEDIR="${APP_HOME}/backend"
export MAVEN_PROJECTBASEDIR

# Locate wrapper resources
WRAPPER_JAR="${APP_HOME}/.mvn/wrapper/maven-wrapper.jar"
WRAPPER_PROPERTIES="${APP_HOME}/.mvn/wrapper/maven-wrapper.properties"

# Read wrapper checksum from properties file
eval $(grep '^wrapperSha256Sum=' "$WRAPPER_PROPERTIES" 2>/dev/null || true)
eval $(grep '^distributionSha256Sum=' "$WRAPPER_PROPERTIES" 2>/dev/null || true)

# Verify wrapper JAR checksum (fail if mismatched — do not silently download)
if [ -n "$wrapperSha256Sum" ] && command -v sha256sum >/dev/null 2>&1; then
  actual=$(sha256sum "$WRAPPER_JAR" 2>/dev/null | cut -d' ' -f1)
  if [ "$actual" != "$wrapperSha256Sum" ]; then
    echo "ERROR: Maven Wrapper JAR checksum mismatch." >&2
    echo "Expected: $wrapperSha256Sum" >&2
    echo "Actual:   $actual" >&2
    echo "Remove ${WRAPPER_JAR} and re-run to download a fresh copy." >&2
    exit 1
  fi
fi

# Collect JVM options
if [ -f "${APP_HOME}/.mvn/jvm.config" ]; then
  while IFS= read -r line; do
    [ -n "$line" ] && [ "${line#\#}" = "$line" ] && MAVEN_OPTS="$MAVEN_OPTS $line"
  done < "${APP_HOME}/.mvn/jvm.config"
fi
MAVEN_OPTS="$MAVEN_OPTS -Dfile.encoding=UTF-8"

# Launch Maven via the wrapper
exec java \
  $MAVEN_OPTS \
  $MAVEN_DEBUG_OPTS \
  -classpath "$WRAPPER_JAR" \
  "-Dmaven.multiModuleProjectDirectory=${MAVEN_PROJECTBASEDIR}" \
  ${WRAPPER_LAUNCHER:-org.apache.maven.wrapper.MavenWrapperMain} \
  -f "${MAVEN_PROJECTBASEDIR}/pom.xml" \
  "$@"
