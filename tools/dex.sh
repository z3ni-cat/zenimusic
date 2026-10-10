#!/bin/bash
# Dex all classes with D8 and add classes.dex to base.apk
set -e
TOOLS="$(cd "$(dirname "$0")" && pwd)"
PROJ="$(dirname "$TOOLS")"
BUILD="$PROJ/build"
SDK="${ANDROID_HOME:-$HOME/workspace/android-sdk}"
D8="$SDK/build-tools/35.0.1/d8"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"

source "$SDK/env.sh" 2>/dev/null || true
if [ -z "$JAVA_HOME" ]; then export JAVA_HOME="$HOME/workspace/jdk/jdk-17.0.20.1+1"; fi

mkdir -p "$BUILD/dex"
INPUTS=()
# D8 only accepts jars/zips: package our compiled classes first
"$JAVA_HOME/bin/jar" cf "$BUILD/app-classes.jar" -C "$BUILD/classes-app" . 2>/dev/null || \
  (cd "$BUILD/classes-app" && "$JAVA_HOME/bin/jar" cf "$BUILD/app-classes.jar" .)
"$JAVA_HOME/bin/jar" cf "$BUILD/r-classes.jar" -C "$BUILD/classes-r" . 2>/dev/null || \
  (cd "$BUILD/classes-r" && "$JAVA_HOME/bin/jar" cf "$BUILD/r-classes.jar" .)
INPUTS+=("$BUILD/app-classes.jar" "$BUILD/r-classes.jar")
for j in "$BUILD"/libs/*.jar; do INPUTS+=("$j"); done
INPUTS+=("$TOOLS/comp-test/kotlin-stdlib-2.0.21.jar")

echo "dex inputs: ${#INPUTS[@]}"
"$D8" --release --min-api 26 --lib "$ANDROID_JAR" \
  --output "$BUILD/dex" \
  "${INPUTS[@]}"

ls -lh "$BUILD/dex"
cd "$BUILD"
for d in "$BUILD"/dex/classes*.dex; do
  cp "$d" "$BUILD/$(basename "$d")"
  zip -q base.apk "$(basename "$d")"
  rm -f "$BUILD/$(basename "$d")"
done
echo "DEX OK"
