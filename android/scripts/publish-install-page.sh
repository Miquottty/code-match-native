#!/usr/bin/env bash
# Publish the side-load install page and the release APK to S3.
#
# The page must be reached over HTTPS: Chrome on Android refuses to download an
# APK from a plain-HTTP origin ("ファイルを安全にダウンロードできません"), and the
# S3 *website* endpoint is HTTP only. Hand out the REST endpoint URL instead,
# which this script prints at the end.
#
# The APK is uploaded with Content-Type application/vnd.android.package-archive;
# with the default binary/octet-stream Chrome's「開く」cannot launch the installer.
#
# Usage:
#   bash android/scripts/publish-install-page.sh [path/to/app-release.apk]
# Environment:
#   CODEMATCH_S3_BUCKET  (default: dl-aaa)
#   CODEMATCH_S3_PREFIX  (default: codematch)
#   CODEMATCH_S3_REGION  (default: ap-northeast-1)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PAGE_DIR="$ROOT/android/install-page"
APK="${1:-$ROOT/android/app/build/outputs/apk/release/app-release.apk}"
BUCKET="${CODEMATCH_S3_BUCKET:-dl-aaa}"
PREFIX="${CODEMATCH_S3_PREFIX:-codematch}"
REGION="${CODEMATCH_S3_REGION:-ap-northeast-1}"
DEST="s3://$BUCKET/$PREFIX"

[[ -f "$APK" ]] || { echo "APK not found: $APK (run ./gradlew :app:assembleRelease first)" >&2; exit 1; }
[[ -f "$PAGE_DIR/index.html" ]] || { echo "install page not found: $PAGE_DIR/index.html" >&2; exit 1; }

echo "Uploading APK ($(du -h "$APK" | cut -f1))"
aws s3 cp "$APK" "$DEST/CodeMatch.apk" \
  --region "$REGION" \
  --content-type application/vnd.android.package-archive \
  --content-disposition 'attachment; filename="CodeMatch.apk"' \
  --cache-control no-cache

# Fill in the version / date shown under the download button.
METADATA="$(dirname "$APK")/output-metadata.json"
VERSION="$(python3 -c 'import json,sys; m=json.load(open(sys.argv[1])); e=m["elements"][0]; print(f'"'"'{e["versionName"]} ({e["versionCode"]})'"'"')' "$METADATA" 2>/dev/null || echo "-")"
DATE="$(date +%Y-%m-%d)"
TMP_DIR="$(mktemp -d -t codematch-install-page)"
trap 'rm -rf "$TMP_DIR"' EXIT

# Rewrite every <img src="…png"> to …png?v=<content hash>, so a phone that has
# cached an older image (the browser keeps images for a day) fetches the new one
# as soon as the page itself is reloaded. Paths are resolved relative to the page.
bust_images() {  # <page.html> <out.html>
  python3 - "$1" "$2" <<'PY'
import hashlib, os, re, sys
src, dst = sys.argv[1], sys.argv[2]
base = os.path.dirname(os.path.abspath(src))
html = open(src, encoding="utf-8").read()
def repl(m):
    path = m.group(1)
    full = os.path.normpath(os.path.join(base, path))
    if not os.path.isfile(full):
        return m.group(0)
    h = hashlib.md5(open(full, "rb").read()).hexdigest()[:8]
    return f'src="{path}?v={h}"'
html = re.sub(r'src="([^"?]+\.png)"', repl, html)
open(dst, "w", encoding="utf-8").write(html)
PY
}

sed -e "s/__VERSION__/$VERSION/g" -e "s/__DATE__/$DATE/g" "$PAGE_DIR/index.html" > "$TMP_DIR/index.src.html"
cp "$TMP_DIR/index.src.html" "$PAGE_DIR/.index.tmp.html"   # sibling of images/ so relative paths resolve
bust_images "$PAGE_DIR/.index.tmp.html" "$TMP_DIR/index.html"
rm -f "$PAGE_DIR/.index.tmp.html"

echo "Uploading page (version $VERSION, $DATE)"
aws s3 cp "$TMP_DIR/index.html" "$DEST/index.html" \
  --region "$REGION" \
  --content-type 'text/html; charset=utf-8' \
  --cache-control no-cache

if compgen -G "$PAGE_DIR/images/*.png" > /dev/null; then
  # cp (not sync): sync skips unchanged files and would leave old metadata in place.
  aws s3 cp "$PAGE_DIR/images" "$DEST/images" --recursive \
    --region "$REGION" \
    --exclude '*' --include '*.png' \
    --content-type image/png \
    --cache-control 'no-cache' 
fi

# Tester guide for Firebase App Distribution (static, no placeholders).
if [[ -f "$PAGE_DIR/firebase/index.html" ]]; then
  echo "Uploading Firebase tester guide"
  bust_images "$PAGE_DIR/firebase/index.html" "$TMP_DIR/firebase.html"
  aws s3 cp "$TMP_DIR/firebase.html" "$DEST/firebase/index.html" \
    --region "$REGION" \
    --content-type 'text/html; charset=utf-8' \
    --cache-control no-cache
  aws s3 cp "$PAGE_DIR/firebase/images" "$DEST/firebase/images" --recursive \
    --region "$REGION" \
    --exclude '*' --include '*.png' \
    --content-type image/png \
    --cache-control 'no-cache' 
fi

echo
echo "Install page:  https://$BUCKET.s3.$REGION.amazonaws.com/$PREFIX/index.html"
echo "Firebase guide: https://$BUCKET.s3.$REGION.amazonaws.com/$PREFIX/firebase/index.html"
