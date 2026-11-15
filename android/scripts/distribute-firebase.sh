#!/usr/bin/env bash
# Upload the release APK to Firebase App Distribution and hand it to testers.
#
# Testers get an e-mail from Firebase with a link; on the phone the link opens
# the App Tester web page, and the APK install itself goes through the same
# Chrome / 不明なアプリ / Play プロテクト dialogs as the S3 page, so the release
# notes link to that page.
#
# Nothing here touches the app: no Firebase SDK is bundled (the release APK must
# stay free of INTERNET, see scripts/verify-release-hardening.sh). Only the
# Firebase CLI on this Mac talks to Google.
#
# Prerequisites (one-time): `npm install -g firebase-tools`, `firebase login`
# with an account that owns project codematch-508312.
#
# Usage:
#   bash android/scripts/distribute-firebase.sh [path/to/app-release.apk] [--testers a@x,b@y | --groups operators] [--notes "..."]
# Default: distribute to the `operators` group (現場オペレーター).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
APK="$ROOT/android/app/build/outputs/apk/release/app-release.apk"
PROJECT="${CODEMATCH_FIREBASE_PROJECT:-codematch-508312}"
APP_ID="${CODEMATCH_FIREBASE_APP_ID:-1:603265025441:android:9a774320c0b55ced2737e0}"
INSTALL_PAGE="${CODEMATCH_INSTALL_PAGE_URL:-https://dl-aaa.s3.ap-northeast-1.amazonaws.com/codematch/index.html}"
TARGET_GROUPS="operators"
TESTERS=""
NOTES=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --testers) TESTERS="$2"; TARGET_GROUPS=""; shift 2 ;;
    --groups) TARGET_GROUPS="$2"; shift 2 ;;
    --notes) NOTES="$2"; shift 2 ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) APK="$1"; shift ;;
  esac
done

[[ -f "$APK" ]] || { echo "APK not found: $APK (run ./gradlew :app:assembleRelease first)" >&2; exit 1; }
command -v firebase >/dev/null || { echo "firebase CLI not installed: npm install -g firebase-tools" >&2; exit 1; }

METADATA="$(dirname "$APK")/output-metadata.json"
VERSION="$(python3 -c 'import json,sys; e=json.load(open(sys.argv[1]))["elements"][0]; print(f'"'"'{e["versionName"]} ({e["versionCode"]})'"'"')' "$METADATA" 2>/dev/null || echo "-")"

if [[ -z "$NOTES" ]]; then
  NOTES="Code Match $VERSION ($(date +%Y-%m-%d))
インストール手順（画面つき）: $INSTALL_PAGE
「不明なアプリ」「Play プロテクト」の警告は Google Play 以外から入れるアプリに必ず出ます。手順ページのとおり進めてください。"
fi

args=(appdistribution:distribute "$APK" --project "$PROJECT" --app "$APP_ID" --release-notes "$NOTES")
[[ -n "$TARGET_GROUPS" ]] && args+=(--groups "$TARGET_GROUPS")
[[ -n "$TESTERS" ]] && args+=(--testers "$TESTERS")

echo "Distributing $APK ($VERSION) to ${TARGET_GROUPS:+group(s) $TARGET_GROUPS}${TESTERS:+testers $TESTERS}"
firebase "${args[@]}"
