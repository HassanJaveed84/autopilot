#!/usr/bin/env bash
# Prints the assetlinks.json statement for a signed APK, using the certificate the APK is actually
# signed with (ChatGPT → App Link spike, docs/CHATGPT_APP_LINK_SPIKE.md). The digest is public; this
# reads no key material and needs no password.
#   usage: assetlinks-from-apk.sh <app-release.apk> [package]   (package defaults to com.lifeadmin.autopilot)
set -euo pipefail
apk="$1"
package="${2:-com.lifeadmin.autopilot}"
apksigner="$(ls -d "${ANDROID_HOME:?set ANDROID_HOME}"/build-tools/*/apksigner | sort -V | tail -1)"
digests="$("$apksigner" verify --print-certs "$apk" | sed -n 's/^Signer #[0-9]* certificate SHA-256 digest: //p')"
[ -n "$digests" ] || { echo "No signer digest found in $apk" >&2; exit 1; }
fingerprints="$(echo "$digests" | tr 'a-f' 'A-F' | sed 's/../&:/g; s/:$//; s/.*/"&"/' | paste -sd, -)"
cat <<JSON
[{
  "relation": ["delegate_permission/common.handle_all_urls"],
  "target": {
    "namespace": "android_app",
    "package_name": "$package",
    "sha256_cert_fingerprints": [$fingerprints]
  }
}]
JSON
