#!/usr/bin/env bash
# Slogr Agent wrapper script — sets JVM options and delegates to the shadow JAR.
set -euo pipefail

JAR="${SLOGR_JAR:-/opt/slogr/slogr-agent-all.jar}"
NATIVE_DIR="${SLOGR_NATIVE_DIR:-/opt/slogr/lib}"
DATA_DIR="${SLOGR_DATA_DIR:-/opt/slogr/data}"
JAVA_OPTS="${JAVA_OPTS:--Xmx384m}"

exec java \
  ${JAVA_OPTS} \
  -Dslogr.native.dir="${NATIVE_DIR}" \
  -Dslogr.data.dir="${DATA_DIR}" \
  -jar "${JAR}" \
  "$@"
