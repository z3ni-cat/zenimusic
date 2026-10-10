#!/bin/bash
# zipalign + sign the APK with the ORIGINAL ZeniMusic keystore (v1.0.0).
# Keystore passwords are read from the key-info file into env vars only,
# never printed or written anywhere.
set -e
PROJ="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="$PROJ/build"
SDK="${ANDROID_HOME:-$HOME/workspace/android-sdk}"
BT="$SDK/build-tools/35.0.1"
BT34="$SDK/build-tools/34.0.0"

source "$SDK/env.sh" 2>/dev/null || true
if [ -z "$JAVA_HOME" ]; then export JAVA_HOME="$HOME/workspace/jdk/jdk-17.0.20.1+1"; fi

INFO="$HOME/workspace/zenimusic-apk/ZeniMusic-signing-key-info.txt"
KS="$HOME/workspace/zenimusic-apk/ZeniMusic-signing.keystore"
OUT="$HOME/workspace/zenimusic-apk/ZeniMusic-v2.5.0.apk"

# Parse passwords (values never echoed)
eval "$(python3 - "$INFO" <<'EOF'
import re, sys, shlex
info = open(sys.argv[1]).read()
def get(label):
    m = re.search(rf"^{label}\s*:\s*(.+)$", info, re.M)
    if not m:
        raise SystemExit(f"missing: {label}")
    return m.group(1).strip()
print("ZKS_PASS=" + shlex.quote(get("Key store password")))
print("ZKS_ALIAS=" + shlex.quote(get("Key alias")))
print("ZKS_KEYPASS=" + shlex.quote(get("Key password")))
EOF
)"

# 1. zipalign
"$BT/zipalign" -f 4 "$BUILD/base.apk" "$BUILD/aligned.apk"

# 2. sign with apksigner (passwords via env)
export ZKS_PASS ZKS_KEYPASS
"$BT/apksigner" sign \
  --ks "$KS" \
  --ks-pass "env:ZKS_PASS" \
  --ks-key-alias "$ZKS_ALIAS" \
  --key-pass "env:ZKS_KEYPASS" \
  --out "$OUT" \
  "$BUILD/aligned.apk"

# 3. scrub env
unset ZKS_PASS ZKS_KEYPASS ZKS_ALIAS

# 4. verify
"$BT/apksigner" verify --print-certs "$OUT" | head -6
echo "--- badging:"
"$BT34/aapt" dump badging "$OUT" | grep -E "^package:|^application-label:" | head -3
ls -lh "$OUT"
echo "SIGN OK"
