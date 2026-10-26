#!/usr/bin/env bash
# Build an update using the existing beta certificate. No keys enter the repo.
set -euo pipefail

project_dir=$(cd "$(dirname "$0")/.." && pwd)
keystore=${FITNOTES_KEYSTORE:-"$HOME/.android/debug.keystore"}
key_alias=${FITNOTES_KEY_ALIAS:-androiddebugkey}
export FITNOTES_STORE_PASSWORD=${FITNOTES_STORE_PASSWORD:-android}
export FITNOTES_KEY_PASSWORD=${FITNOTES_KEY_PASSWORD:-"$FITNOTES_STORE_PASSWORD"}
expected_cert=7ab64af6c9ab261ba03a9471b6a3adc59fa33d684d5d1c4ef1f06a6bc693ddce
sdk_dir=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [[ -z "$sdk_dir" && -d "$HOME/Library/Android/sdk" ]]; then
    sdk_dir="$HOME/Library/Android/sdk"
fi
tools_dir="$sdk_dir/build-tools/${FITNOTES_BUILD_TOOLS:-36.0.0}"

if [[ ! -f "$keystore" ]]; then
    printf 'Existing signing key missing: %s\nSet FITNOTES_KEYSTORE to the key used for beta.1.\n' "$keystore" >&2
    exit 1
fi
keystore="$(cd "$(dirname "$keystore")" && pwd)/$(basename "$keystore")"
for tool in zipalign apksigner aapt2; do
    if [[ ! -x "$tools_dir/$tool" ]]; then
        printf 'Missing Android SDK tool: %s\nSet ANDROID_HOME and install Build Tools 36.0.0.\n' "$tools_dir/$tool" >&2
        exit 1
    fi
done

temporary_dir=$(mktemp -d)
trap 'rm -rf "$temporary_dir"' EXIT
keytool -exportcert -keystore "$keystore" -alias "$key_alias" \
    -storepass:env FITNOTES_STORE_PASSWORD -file "$temporary_dir/certificate.der" >/dev/null
if command -v sha256sum >/dev/null; then
    cert=$(sha256sum "$temporary_dir/certificate.der" | awk '{print $1}')
else
    cert=$(shasum -a 256 "$temporary_dir/certificate.der" | awk '{print $1}')
fi
if [[ "$cert" != "$expected_cert" ]]; then
    printf 'This key does not match beta.1. Keep the installed app and locate its original signing key.\n' >&2
    exit 1
fi

cd "$project_dir"
export ANDROID_HOME="$sdk_dir"
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease --console=plain
metadata=app/build/outputs/apk/release/output-metadata.json
version=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["elements"][0]["versionName"])' "$metadata")
version_code=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["elements"][0]["versionCode"])' "$metadata")
unsigned_name=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["elements"][0]["outputFile"])' "$metadata")
apk_name="fitnotes-$version.apk"
"$tools_dir/zipalign" -f -P 16 4 "app/build/outputs/apk/release/$unsigned_name" "$temporary_dir/aligned.apk"
"$tools_dir/apksigner" sign --ks "$keystore" --ks-key-alias "$key_alias" \
    --ks-pass env:FITNOTES_STORE_PASSWORD --key-pass env:FITNOTES_KEY_PASSWORD \
    --out "$temporary_dir/$apk_name" "$temporary_dir/aligned.apk"
"$tools_dir/apksigner" verify --verbose --print-certs "$temporary_dir/$apk_name" > "$temporary_dir/verification.txt"
grep -Fq "Signer #1 certificate SHA-256 digest: $expected_cert" "$temporary_dir/verification.txt"
"$tools_dir/zipalign" -c -P 16 4 "$temporary_dir/$apk_name"
"$tools_dir/aapt2" dump badging "$temporary_dir/$apk_name" > "$temporary_dir/badging.txt"
grep -Fq "package: name='com.example.fitnotes' versionCode='$version_code' versionName='$version'" "$temporary_dir/badging.txt"
grep -Eq "^(minSdkVersion|sdkVersion):'24'$" "$temporary_dir/badging.txt"
if grep -q '^application-debuggable' "$temporary_dir/badging.txt"; then
    printf 'Refusing to package a debuggable release.\n' >&2
    exit 1
fi
output_dir="$project_dir/build/release"
mkdir -p "$output_dir"
mv "$temporary_dir/$apk_name" "$output_dir/$apk_name"
cd "$output_dir"
if command -v sha256sum >/dev/null; then
    sha256sum "$apk_name" > SHA256SUMS
else
    shasum -a 256 "$apk_name" > SHA256SUMS
fi
printf 'Verified update APK: %s/%s\nChecksum: %s/SHA256SUMS\n' "$output_dir" "$apk_name" "$output_dir"
