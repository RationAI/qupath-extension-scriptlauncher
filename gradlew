#!/usr/bin/env sh
# Gradle wrapper launcher - minimal wrapper script
# This script expects gradle/wrapper/gradle-wrapper.jar to be present

set -e

# Resolve the directory containing this script
PRG="$0"
while [ -h "$PRG" ]; do
  ls=$(ls -ld "$PRG")
  link=$(expr "$ls" : '.*-> \(.*\)$')
  if expr "$link" : '/.*' > /dev/null; then
    PRG="$link"
  else
    PRG="$(dirname "$PRG")/$link"
  fi
done

SAVED="$(pwd)"
cd "$(dirname "$PRG")" >/dev/null
BASE_DIR="$(pwd -P)"
cd "$SAVED" >/dev/null

WRAPPER_JAR="$BASE_DIR/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$WRAPPER_JAR" ]; then
  echo "ERROR: Gradle wrapper jar not found at $WRAPPER_JAR" >&2
  exit 1
fi

# Use JAVA_HOME if set
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA_CMD="$JAVA_HOME/bin/java"
else
  JAVA_CMD="java"
fi

exec "$JAVA_CMD" -jar "$WRAPPER_JAR" "$@"
