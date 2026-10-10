#!/bin/bash
# Add META-INF/services/* resource files into base.apk.
#
# PERMANENT FIX for the v2.0.0/v2.0.1 launch crash ("ZeniMusic terus berhenti"):
# the manual D8 pipeline drops META-INF/services/* files that AGP/Gradle
# merges automatically. Without them:
#   - kotlinx.coroutines.internal.MainDispatcherFactory is missing ->
#     Dispatchers.Main throws IllegalStateException('Module with the Main
#     dispatcher is missing') the moment PlayerManager.<clinit> runs in
#     ZeniMusicApp.onCreate -> instant crash at launch.
#   - kotlinx.coroutines.CoroutineExceptionHandler (AndroidExceptionPreHandler)
#     and javax.script.ScriptEngineFactory (Rhino, needed by NewPipe's JS
#     deciphering for stream URLs) are missing too.
# NEVER remove this step: without it Dispatchers.Main crashes at startup.
#
# Must run AFTER dex.sh (which fills base.apk) and BEFORE sign.sh
# (zipalign + apksigner must see the final content).
set -e
TOOLS="$(cd "$(dirname "$0")" && pwd)"
PROJ="$(dirname "$TOOLS")"
BUILD="$PROJ/build"
SERVICES_META="$BUILD/services-meta"

if [ ! -d "$SERVICES_META/META-INF/services" ]; then
    echo "services-meta missing: $SERVICES_META/META-INF/services" >&2
    exit 1
fi
if [ ! -f "$BUILD/base.apk" ]; then
    echo "base.apk missing: run dex.sh first" >&2
    exit 1
fi

# Zip from inside services-meta so entries land as META-INF/services/...
cd "$SERVICES_META"
zip -q -r "$BUILD/base.apk" META-INF
echo "added META-INF/services to base.apk:"
unzip -l "$BUILD/base.apk" | grep "META-INF/services" || {
    echo "FAILED to add services files!" >&2
    exit 1
}
echo "ADD-SERVICES OK"
