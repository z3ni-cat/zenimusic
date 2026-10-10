#!/bin/bash
# Compile app Kotlin sources with kotlinc + Compose plugin (manual AGP replacement).
set -e
TOOLS="$(cd "$(dirname "$0")" && pwd)"
PROJ="$(dirname "$TOOLS")"
BUILD="$PROJ/build"
SDK="${ANDROID_HOME:-$HOME/workspace/android-sdk}"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"

source "$SDK/env.sh" 2>/dev/null || true
if [ -z "$JAVA_HOME" ]; then export JAVA_HOME="$HOME/workspace/jdk/jdk-17.0.20.1+1"; fi

KC_CP="$(cat "$TOOLS/kotlinc-cp.txt")"
PLUGIN="$TOOLS/kotlin-compose-compiler-plugin-embeddable-2.0.21.jar"
STDLIB="$TOOLS/comp-test/kotlin-stdlib-2.0.21.jar"

# App classpath: all dependency jars + R classes + android.jar + stdlib
APP_CP="$STDLIB:$ANDROID_JAR:$BUILD/classes-r"
for j in "$BUILD"/libs/*.jar; do
  APP_CP="$APP_CP:$j"
done

mkdir -p "$BUILD/classes-app"
SOURCES=$(find "$PROJ/app/src/main/java" -name "*.kt" | sort)
echo "sources: $(echo "$SOURCES" | wc -l) files"
echo "classpath entries: $(echo "$APP_CP" | tr ':' '\n' | wc -l)"

# shellcheck disable=SC2086
"$JAVA_HOME/bin/java" -Xmx1400m -cp "$KC_CP" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect \
  -jvm-target 17 \
  -Xplugin="$PLUGIN" \
  -cp "$APP_CP" \
  -d "$BUILD/classes-app" \
  $SOURCES

echo "KOTLIN COMPILE OK"
