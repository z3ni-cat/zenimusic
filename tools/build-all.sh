#!/bin/bash
# Master build script for ZeniMusic native (manual AGP replacement).
# Gradle cannot run in this sandbox (Java processes can't open TCP sockets,
# which breaks the Gradle daemon IPC), so the APK is built directly with
# aapt2 + kotlinc + D8 + apksigner.
#
# Full pipeline (run once):  resolve.py -> prep.py -> merge_res.py -> link_res.py
# Incremental rebuild:       ./compile.sh && ./dex.sh && ./add-services.sh && ./sign.sh
#
# NOTE: add-services.sh is a PERMANENT FIX, not optional. The manual D8
# pipeline drops META-INF/services/* files that AGP merges automatically;
# without them Dispatchers.Main throws IllegalStateException('Module with
# the Main dispatcher is missing') at startup and the app crashes instantly
# (this was the v2.0.0/v2.0.1 "terus berhenti" bug). It must run after dex.sh
# (base.apk exists) and before sign.sh (zipalign+sign must see final content).
set -e
TOOLS="$(cd "$(dirname "$0")" && pwd)"
# Versi diambil dari link_res.py (single source of truth) agar nama APK
# hasil sign.sh tidak pernah basi.
export ZM_VERSION
ZM_VERSION="$(grep -oP '"--version-name", "\K[^"]+' "$TOOLS/link_res.py" | head -1)"
"$TOOLS/compile.sh" && "$TOOLS/dex.sh" && "$TOOLS/add-services.sh" && "$TOOLS/sign.sh"
echo "BUILD ALL DONE"
