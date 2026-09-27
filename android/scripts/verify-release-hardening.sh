#!/usr/bin/env bash

# Release privacy and packaging checks for the side-loaded release APK.
#
# The compiled APK is the only artifact that ships, so every rule is checked
# against it (manifest, permissions, compiled backup/FileProvider resources,
# DEX contents, native libraries) plus the resolved release dependency graph
# and a static scan of production sources. Only Android SDK tools and standard
# Unix tools are used; no Gradle plugin or generated lock/SBOM file is needed.

set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
android_root="$(cd -- "$script_dir/.." && pwd -P)"
app_dir="$android_root/app"

release_apk="$app_dir/build/outputs/apk/release/app-release.apk"
dependency_report=""
tmp_dir=""

usage() {
    cat <<'EOF'
Usage: verify-release-hardening.sh [--apk PATH] [--dependency-report PATH]

Checks the release APK (manifest, permissions, compiled backup and FileProvider
resources, DEX, native libraries), the optional releaseRuntimeClasspath report,
and production sources. Run from any directory.

  --apk PATH                  Release APK (default: app/build/outputs/apk/release/app-release.apk)
  --dependency-report PATH    Output of `gradlew :app:dependencies --configuration releaseRuntimeClasspath`
  --help                      Show this help

Release bundles the official Inateck scanner SDK and may request only
BLUETOOTH_SCAN (neverForLocation) and BLUETOOTH_CONNECT beyond CAMERA and
VIBRATE. Legacy Bluetooth, location, advertising, and network permissions stay
forbidden; Fake/debug scanner entry points and analytics SDKs must not leak
into release.
EOF
}

die() {
    printf 'Release hardening check failed: %s\n' "$*" >&2
    exit 1
}

note() {
    printf '[release-hardening] %s\n' "$*"
}

while (($# > 0)); do
    case "$1" in
        --apk)
            (($# >= 2)) || die "--apk requires a path"
            release_apk="$2"
            shift 2
            ;;
        --dependency-report)
            (($# >= 2)) || die "--dependency-report requires a path"
            dependency_report="$2"
            shift 2
            ;;
        --help|-h)
            usage
            exit 0
            ;;
        *)
            die "unknown option: $1"
            ;;
    esac
done

[[ -f "$release_apk" ]] || die "release APK is missing: $release_apk"

find_aapt2() {
    local sdk_root candidate
    if [[ -n "${AAPT2:-}" && -x "${AAPT2}" ]]; then
        printf '%s\n' "$AAPT2"
        return 0
    fi
    for sdk_root in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk"; do
        [[ -n "$sdk_root" && -d "$sdk_root/build-tools" ]] || continue
        candidate="$(find "$sdk_root/build-tools" -type f -name aapt2 -perm -111 -print 2>/dev/null | sort | tail -n 1)"
        if [[ -n "$candidate" ]]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done
    return 1
}

aapt2="$(find_aapt2)" || die "aapt2 is unavailable; set ANDROID_HOME"
apkanalyzer="$(dirname "$(dirname "$(dirname "$aapt2")")")/cmdline-tools/latest/bin/apkanalyzer"
[[ -x "$apkanalyzer" ]] || die "apkanalyzer is unavailable: $apkanalyzer"

tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/codematch-release-hardening.XXXXXX")"
trap 'rm -rf "$tmp_dir"' EXIT

manifest="$tmp_dir/manifest.txt"
permissions="$tmp_dir/permissions.txt"
resources="$tmp_dir/resources.txt"
entries="$tmp_dir/entries.txt"
"$aapt2" dump xmltree --file AndroidManifest.xml "$release_apk" > "$manifest" || die "aapt2 could not read the manifest"
"$aapt2" dump permissions "$release_apk" > "$permissions" || die "aapt2 could not read permissions"
"$aapt2" dump resources "$release_apk" > "$resources" || die "aapt2 could not read resources"
unzip -Z1 "$release_apk" > "$entries" || die "could not list APK entries"

# --- Permissions ------------------------------------------------------------

forbidden_permissions=(
    INTERNET ACCESS_NETWORK_STATE
    BLUETOOTH BLUETOOTH_ADMIN BLUETOOTH_ADVERTISE BLUETOOTH_PRIVILEGED
    ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION ACCESS_BACKGROUND_LOCATION
    NEARBY_WIFI_DEVICES UWB_RANGING
)
for permission in "${forbidden_permissions[@]}"; do
    if grep -q -F "name='android.permission.$permission'" "$permissions"; then
        die "APK requests forbidden permission android.permission.$permission"
    fi
done
grep -q -F "uses-permission: name='android.permission.BLUETOOTH_SCAN' usesPermissionFlags='neverForLocation'" "$permissions" || \
    die "BLUETOOTH_SCAN with neverForLocation is missing"
grep -q -F "uses-permission: name='android.permission.BLUETOOTH_CONNECT'" "$permissions" || \
    die "BLUETOOTH_CONNECT is missing"
note "permissions: only CAMERA, VIBRATE, BLUETOOTH_SCAN (neverForLocation), BLUETOOTH_CONNECT"

# --- Manifest ---------------------------------------------------------------

# Resource ids referenced from the manifest must resolve to the expected
# compiled resources; the ids are looked up from the resource table.
resource_id() {
    awk -v name="$1" '$1 == "resource" && $3 == name { print $2; exit }' "$resources"
}
resource_file() {
    awk -v name="$1" '
        $1 == "resource" && $3 == name { found = 1; next }
        found { for (i = 1; i <= NF; i++) if ($i ~ /^res\//) { print $i; exit } }
    ' "$resources"
}
backup_rules_id="$(resource_id xml/backup_rules)"
data_extraction_rules_id="$(resource_id xml/data_extraction_rules)"
file_paths_id="$(resource_id xml/file_paths)"
[[ "$backup_rules_id" =~ ^0x ]] || die "xml/backup_rules is not compiled into the APK"
[[ "$data_extraction_rules_id" =~ ^0x ]] || die "xml/data_extraction_rules is not compiled into the APK"
[[ "$file_paths_id" =~ ^0x ]] || die "xml/file_paths is not compiled into the APK"

! grep -q -E ':debuggable\([^)]*\)=true' "$manifest" || die "APK is debuggable"
grep -q -E ':allowBackup\([^)]*\)=false' "$manifest" || die "application must set android:allowBackup=false"
grep -q -E ":fullBackupContent\([^)]*\)=@$backup_rules_id\$" "$manifest" || \
    die "application does not reference xml/backup_rules from fullBackupContent"
grep -q -E ":dataExtractionRules\([^)]*\)=@$data_extraction_rules_id\$" "$manifest" || \
    die "application does not reference xml/data_extraction_rules from dataExtractionRules"

# One line per component: kind<TAB>name<TAB>exported (attributes belong to the
# component only at its own indentation, so meta-data names are not confused
# with the component name).
components="$tmp_dir/components.txt"
awk '
    function flush() { if (kind != "") print kind "\t" name "\t" exported; kind = "" }
    {
        match($0, /^[ ]*/); indent = RLENGTH
        if ($0 ~ /^ *E: (activity|activity-alias|service|receiver|provider) /) {
            flush(); kind = $2; comp_indent = indent; name = ""; exported = "unset"; next
        }
        if (kind != "" && $0 ~ /^ *E: / && indent <= comp_indent) flush()
        if (kind != "" && indent == comp_indent + 2) {
            if (name == "" && $0 ~ /:name\(0x01010003\)="/) {
                name = $0; sub(/.*:name\(0x01010003\)="/, "", name); sub(/".*/, "", name)
            }
            if ($0 ~ /:exported\([^)]*\)=true/) exported = "true"
            if ($0 ~ /:exported\([^)]*\)=false/) exported = "false"
        }
    }
    END { flush() }
' "$manifest" > "$components"
exported_components="$(awk -F '\t' '$3 == "true" { print $2 }' "$components")"
[[ "$exported_components" == "jp.rimtty.codematch.MainActivity" ]] || \
    die "exported components must be exactly MainActivity, found: ${exported_components:-none}"
for forbidden_component in \
    androidx.compose.ui.tooling.PreviewActivity \
    androidx.activity.ComponentActivity \
    androidx.profileinstaller.ProfileInstallReceiver; do
    ! awk -F '\t' '{ print $2 }' "$components" | grep -q -x -F "$forbidden_component" || \
        die "debug/tooling component is packaged: $forbidden_component"
done

# Subtree of the FileProvider element (until the next element at its indent).
provider_block="$(awk '
    {
        match($0, /^[ ]*/); indent = RLENGTH
        if (capturing && $0 ~ /^ *E: / && indent <= provider_indent) exit
        if (capturing) { print; next }
        if ($0 ~ /^ *E: provider /) { pending = 1; provider_indent = indent; next }
        if (pending && indent == provider_indent + 2 && $0 ~ /:name\(0x01010003\)="androidx\.core\.content\.FileProvider"/) {
            capturing = 1; print
        } else if (pending && $0 ~ /^ *E: /) pending = 0
    }
' "$manifest")"
[[ -n "$provider_block" ]] || die "FileProvider is missing from the manifest"
grep -q -E ':exported\([^)]*\)=false' <<< "$provider_block" || die "FileProvider must be non-exported"
grep -q -E ':grantUriPermissions\([^)]*\)=true' <<< "$provider_block" || die "FileProvider must grant URI permissions"
grep -q -F 'android.support.FILE_PROVIDER_PATHS' <<< "$provider_block" || die "FileProvider paths meta-data is missing"
grep -q -E ":resource\([^)]*\)=@$file_paths_id\$" <<< "$provider_block" || \
    die "FileProvider meta-data does not reference xml/file_paths"

! grep -q -i -E 'scanner[/:.]fake|FakeExternalScanner|show_debug_demo_tools' "$manifest" || \
    die "manifest contains a Fake/debug release entry"
note "manifest: not debuggable, backup disabled, single exported MainActivity, scoped FileProvider"

# --- Compiled resources -----------------------------------------------------

demo_tools_value="$(awk '
    $1 == "resource" && $3 == "bool/show_debug_demo_tools" { found = 1; next }
    found { print $NF; exit }
' "$resources")"
[[ "$demo_tools_value" == "false" ]] || die "bool/show_debug_demo_tools must compile to false (found: ${demo_tools_value:-missing})"

compiled_xml() {
    local file
    file="$(resource_file "$1")"
    [[ -n "$file" ]] || die "compiled resource $1 is missing"
    "$aapt2" dump xmltree --file "$file" "$release_apk" || die "aapt2 could not read $1"
}

# Prints section|domain|path for every <exclude> under the named sections.
exclude_pairs() {
    awk '
        function flush() {
            if (in_exclude && section != "" && domain != "" && path != "") print section "|" domain "|" path
            in_exclude = 0; domain = ""; path = ""
        }
        /^ *E: (full-backup-content|cloud-backup|device-transfer)( |$)/ { flush(); section = $2; next }
        /^ *E: exclude( |$)/ { flush(); in_exclude = 1; next }
        in_exclude && /A: domain="/ { domain = $0; sub(/.*A: domain="/, "", domain); sub(/".*/, "", domain); next }
        in_exclude && /A: path="/ { path = $0; sub(/.*A: path="/, "", path); sub(/".*/, "", path); next }
        END { flush() }
    '
}

required_exclusions='root|.
database|.
file|datastore/
file|datastore/codematch-ble-symbology.preferences_pb
file|ble/
sharedpref|.
external|.
device_root|.
device_database|.
device_file|.
device_sharedpref|.'

check_backup_dump() {
    local dump="$1" label="$2"
    shift 2
    local pairs section domain path
    ! grep -q -E '^ *E: include( |$)' <<< "$dump" || die "$label must not contain backup includes"
    pairs="$(exclude_pairs <<< "$dump")"
    for section in "$@"; do
        while IFS='|' read -r domain path; do
            grep -q -x -F "$section|$domain|$path" <<< "$pairs" || \
                die "$label must exclude domain=$domain path=$path under $section"
        done <<< "$required_exclusions"
    done
}

check_backup_dump "$(compiled_xml xml/backup_rules)" "backup_rules" full-backup-content
check_backup_dump "$(compiled_xml xml/data_extraction_rules)" "data_extraction_rules" cloud-backup device-transfer

file_paths_dump="$(compiled_xml xml/file_paths)"
grep -q -E '^ *E: paths( |$)' <<< "$file_paths_dump" || die "FileProvider paths root is missing"
# Two scoped cache paths: the PDF report and the JSON history export. Each one
# is a dedicated subdirectory, so no other cache content is ever shareable.
[[ "$(grep -c -E '^ *E: cache-path( |$)' <<< "$file_paths_dump")" == "2" ]] || \
    die "FileProvider must expose exactly two cache-paths"
! grep -q -E '^ *E: (files-path|external-path|root-path|external-files-path|external-cache-path|external-media-path)( |$)' <<< "$file_paths_dump" || \
    die "FileProvider exposes a broad or external path"
grep -q -F 'A: name="history_pdf"' <<< "$file_paths_dump" || die "FileProvider cache path name is not history_pdf"
grep -q -F 'A: path="codematch-pdf/"' <<< "$file_paths_dump" || die "FileProvider cache path is not codematch-pdf/"
grep -q -F 'A: name="history_export"' <<< "$file_paths_dump" || die "FileProvider cache path name is not history_export"
grep -q -F 'A: path="codematch-export/"' <<< "$file_paths_dump" || die "FileProvider cache path is not codematch-export/"
note "resources: demo tools off, backup/transfer exclusions present, FileProvider limited to cache/codematch-pdf/ and cache/codematch-export/"

# --- DEX and native libraries -----------------------------------------------

dex_strings="$tmp_dir/dex-strings.txt"
grep -E '^classes[0-9]*\.dex$' "$entries" | while IFS= read -r dex; do
    unzip -p "$release_apk" "$dex"
done | strings > "$dex_strings"
[[ -s "$dex_strings" ]] || die "no DEX content found in the APK"

if grep -n -i -E 'jp/rimtty/codematch/scanner/fake|FakeExternalScanner|FAKE-BCST-47' "$dex_strings"; then
    die "APK contains Fake scanner classes or identifiers"
fi
grep -q -F 'jp/rimtty/codematch/scanner/inateck' "$dex_strings" || die "APK does not contain the Inateck scanner adapter"
if grep -n -i -E 'com/google/firebase/analytics|com/google/firebase/crashlytics|com/google/android/gms/analytics|io/sentry|com/bugsnag|com/newrelic|com/datadog|com/mixpanel|com/amplitude|com/segment|com/posthog|com/countly' "$dex_strings"; then
    die "APK contains analytics or crash-reporting classes"
fi
# R8 must strip the vendor SDK's raw-payload logging (see app/scanner-rules.pro).
if grep -n -i -E 'notify (00|01).*data|notify 00----- onDisConnected completion|setSettingInfo result|getSettingInfo result' "$dex_strings"; then
    die "vendor raw-log strings remain in DEX"
fi

for native_lib in libjnidispatch.so libscanner_cmd.so libinateck_scanner_cmd.so; do
    grep -q -x -F "lib/arm64-v8a/$native_lib" "$entries" || die "Inateck native library is missing: $native_lib"
done
if grep -E '^lib/' "$entries" | grep -v -E '^lib/arm64-v8a/' | grep -q .; then
    die "APK contains native libraries for an unexpected ABI"
fi

# ML Kit constructs these registrars reflectively; R8 must keep public no-arg constructors.
for registrar in \
    com.google.mlkit.vision.barcode.internal.BarcodeRegistrar \
    com.google.mlkit.vision.common.internal.VisionCommonRegistrar \
    com.google.mlkit.common.internal.CommonComponentRegistrar; do
    "$apkanalyzer" dex code --class "$registrar" --method '<init>()V' "$release_apk" > "$tmp_dir/registrar.txt" 2>/dev/null || \
        die "ML Kit registrar constructor missing: $registrar"
    grep -q -E '\.method public constructor <init>\(\)V' "$tmp_dir/registrar.txt" || \
        die "ML Kit registrar constructor is not public: $registrar"
done
note "dex/native: no Fake or analytics classes, Inateck adapter and arm64-v8a libraries present, vendor logs stripped, ML Kit registrars kept"

# --- Dependency graph -------------------------------------------------------

analytics_coordinates='firebase-analytics|firebase-crashlytics|sentry|bugsnag|newrelic|datadog|appcenter|instabug|rollbar|raygun|airbrake|hockeyapp|mixpanel|amplitude|segment|posthog|countly|telemetry'
if [[ -n "$dependency_report" ]]; then
    [[ -f "$dependency_report" ]] || die "dependency report is missing: $dependency_report"
    ! grep -n -F ':scanner:fake' "$dependency_report" || die "Fake scanner leaked into the release dependency graph"
    grep -q -F ':scanner:inateck' "$dependency_report" || die "release dependency graph is missing :scanner:inateck"
    ! grep -n -i -E "$analytics_coordinates" "$dependency_report" || die "analytics/crash dependency found in release graph"
    note "dependency graph: :scanner:inateck present, no Fake, analytics, or crash SDK"
else
    note "no dependency report supplied; skipping release graph check"
fi

# --- Production sources -----------------------------------------------------

# Camera frames and payloads must never be persisted or logged, and no
# analytics/crash SDK may be referenced from production code or build files.
gradle_hits="$(grep -rn -i -E "$analytics_coordinates" "$android_root" --include='*.gradle' --include='*.gradle.kts' --include='*.toml' --exclude-dir=build || true)"
[[ -z "$gradle_hits" ]] || die "analytics/crash dependency coordinate found:"$'\n'"$gradle_hits"

production_dirs=()
while IFS= read -r directory; do
    production_dirs+=("$directory")
done < <(find "$android_root" -path '*/build' -prune -o -path '*/src/main' -type d -print | sort)
((${#production_dirs[@]} > 0)) || die "no production source directories found"

source_hits="$(grep -rn -i -E \
    'android\.provider\.MediaStore|Bitmap\.compress|ImageCapture|\.takePicture\(|openFileOutput|FileOutputStream|getExternalFilesDir|externalFilesDir|writeBytes|writeText|printStackTrace|Log\.(v|d|i|w|e)\(.*(payload|frame|image)|println\(.*(payload|frame|image)' \
    "${production_dirs[@]}" --include='*.kt' --include='*.java' || true)"
[[ -z "$source_hits" ]] || die "production source persists or logs frames/images/payloads:"$'\n'"$source_hits"

# The only production code allowed to create files is the export layer: the
# PDF report, the JSON history and scan-log exports, and the diagnostics mail
# attachment, each writing into a scoped app-private cache subdirectory that
# the FileProvider exposes.
file_hits="$(grep -rn -E '(^|[^[:alnum:]_])File[[:space:]]*\(' "${production_dirs[@]}" --include='*.kt' --include='*.java' | grep -v -E 'core/export/src/main/.*/(History(Pdf|Json)|ScanLogJson|BluetoothDiagnostics)Exporter\.kt:' || true)"
[[ -z "$file_hits" ]] || die "production source creates files outside the dedicated exporters:"$'\n'"$file_hits"

analytics_hits="$(grep -rn -i -E 'FirebaseAnalytics|FirebaseCrashlytics|Crashlytics|Sentry|Bugsnag|NewRelic|Datadog|Mixpanel|PostHog|Countly|AnalyticsTracker|CrashReporter' \
    "${production_dirs[@]}" --include='*.kt' --include='*.java' || true)"
[[ -z "$analytics_hits" ]] || die "production source references analytics/crash reporting:"$'\n'"$analytics_hits"
note "sources: no frame/payload persistence or analytics references"

note "all release hardening checks passed"
