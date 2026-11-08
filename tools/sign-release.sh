#!/usr/bin/env bash
# Signs a release APK with the Ultimate Blocker release key, creating the key on first run.
#
#   tools/sign-release.sh <unsigned.apk> <signed-output.apk>
#
# The key lives outside the repo (override with UB_KEYSTORE). Back it up: every future
# update must be signed with it, or users have to uninstall (losing settings) to update.
set -euo pipefail

if [ $# -ne 2 ]; then
  echo "Usage: $0 <unsigned.apk> <signed-output.apk>" >&2
  exit 1
fi
IN=$1
OUT=$2
KEYSTORE="${UB_KEYSTORE:-$HOME/.android-keys/ultimate-blocker-release.jks}"
ALIAS=ultimate-blocker
BUILD_TOOLS=$(ls -d "${ANDROID_HOME:-$HOME/Library/Android/sdk}/build-tools/"* | sort -V | tail -1)

read -r -s -p "Release key password: " UB_KEY_PASSWORD; echo
export UB_KEY_PASSWORD

if [ ! -f "$KEYSTORE" ]; then
  read -r -s -p "No key yet; confirm password to create one: " confirm; echo
  [ "$UB_KEY_PASSWORD" = "$confirm" ] || { echo "Passwords differ." >&2; exit 1; }
  [ ${#UB_KEY_PASSWORD} -ge 8 ] || { echo "Use at least 8 characters." >&2; exit 1; }
  mkdir -p "$(dirname "$KEYSTORE")"
  chmod 700 "$(dirname "$KEYSTORE")"
  keytool -genkeypair -keystore "$KEYSTORE" -storetype PKCS12 -alias "$ALIAS" \
    -keyalg RSA -keysize 4096 -validity 10000 \
    -dname "CN=Naman Kumar, O=Ultimate Blocker" \
    -storepass:env UB_KEY_PASSWORD -keypass:env UB_KEY_PASSWORD
  chmod 600 "$KEYSTORE"
  echo "Created $KEYSTORE. Back it up somewhere safe, along with the password."
fi

aligned=$(mktemp -t ub-aligned).apk
trap 'rm -f "$aligned"' EXIT
"$BUILD_TOOLS/zipalign" -f -p 4 "$IN" "$aligned"
"$BUILD_TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "$ALIAS" \
  --ks-pass env:UB_KEY_PASSWORD --key-pass env:UB_KEY_PASSWORD --out "$OUT" "$aligned"
"$BUILD_TOOLS/apksigner" verify --print-certs "$OUT" | grep -E 'DN|SHA-256'
echo "Signed: $OUT"
