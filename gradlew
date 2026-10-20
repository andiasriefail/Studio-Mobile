#!/bin/sh
# Wrapper launcher. A genuine gradle-wrapper.jar must exist in gradle/wrapper/.
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P) || exit
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$CLASSPATH" ]; then
  echo "ERROR: gradle/wrapper/gradle-wrapper.jar is missing." >&2
  echo "Generate it using a trusted Gradle 9.8.1 installation: gradle wrapper --gradle-version 9.8.1 --distribution-type bin" >&2
  exit 1
fi
exec java -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
