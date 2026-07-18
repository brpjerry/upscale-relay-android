#!/usr/bin/env sh
set -eu
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$JAR" ]; then
  echo "Missing Gradle wrapper JAR. Run ./bootstrap-wrapper.sh first." >&2
  exit 1
fi
exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" -classpath "$JAR" org.gradle.wrapper.GradleWrapperMain "$@"

