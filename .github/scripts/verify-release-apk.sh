#!/usr/bin/env bash
# Checks the release APK before it is installed over the phone's existing Autopilot:
# application ID, version, the /autopilot/import App Link (and no retired /autopilot/test route),
# no INTERNET permission, and the signing certificate against the published assetlinks.json.
#   usage: verify-release-apk.sh <app-release.apk> <expected versionCode> <expected versionName>
# Exit status: 0 = everything matches, including the personal release certificate;
#              2 = everything matches but the APK is NOT signed with the published certificate
#                  (e.g. no personal key available): never install it over the existing app;
#              1 = any other mismatch.
set -euo pipefail
apk="$1"; want_code="$2"; want_name="$3"
tools="$(ls -d "${ANDROID_HOME:?set ANDROID_HOME}"/build-tools/* | sort -V | tail -1)"
fail=0
check() { if [ "$2" = "$3" ]; then echo "OK   $1: $2"; else echo "FAIL $1: got '$2', expected '$3'"; fail=1; fi; }

badging="$("$tools/aapt2" dump badging "$apk")"
pkg_line="$(echo "$badging" | grep -m1 '^package:')"
check "applicationId" "$(echo "$pkg_line" | sed -nE "s/.* name='([^']+)'.*/\1/p")" "com.lifeadmin.autopilot"
check "versionCode" "$(echo "$pkg_line" | sed -nE "s/.* versionCode='([^']+)'.*/\1/p")" "$want_code"
check "versionName" "$(echo "$pkg_line" | sed -nE "s/.* versionName='([^']+)'.*/\1/p")" "$want_name"

perms="$(echo "$badging" | sed -nE "s/^uses-permission: name='([^']+)'.*/\1/p" | sort)"
echo "Permissions:"; echo "$perms" | sed 's/^/     /'
if echo "$perms" | grep -qx 'android.permission.INTERNET'; then echo "FAIL INTERNET permission is requested"; fail=1; else echo "OK   no INTERNET permission"; fi

manifest="$("$tools/aapt2" dump xmltree --file AndroidManifest.xml "$apk")"
if echo "$manifest" | grep -q '"/autopilot/import"'; then echo "OK   /autopilot/import App Link filter present"; else echo "FAIL /autopilot/import filter missing"; fail=1; fi
if echo "$manifest" | grep -q '/autopilot/test'; then echo "FAIL retired /autopilot/test route still declared"; fail=1; else echo "OK   no /autopilot/test route"; fi
if echo "$manifest" | grep -q 'AppLinkTestActivity'; then echo "FAIL AppLinkTestActivity still declared"; fail=1; fi

"$tools/apksigner" verify "$apk" >/dev/null && echo "OK   APK signature verifies" || { echo "FAIL APK signature does not verify"; fail=1; }
digest="$("$tools/apksigner" verify --print-certs "$apk" | sed -nE 's/^(V[0-9.]+ )?Signer[^:]*:? *certificate SHA-256 digest: *([0-9a-fA-F]{64})$/\2/p' | sort -u | tr 'a-f' 'A-F' | sed 's/../&:/g; s/:$//')"
published="$(sed -nE 's/.*"sha256_cert_fingerprints": *\["([0-9A-F:]+)"\].*/\1/p' "$(dirname "$0")/../../applink-site/.well-known/assetlinks.json")"
echo "Signer SHA-256:    $digest"
echo "Published (site):  $published"
[ "$fail" -eq 0 ] || exit 1
if [ -n "$digest" ] && [ "$digest" = "$published" ]; then
  echo "OK   signed with the personal release certificate published in assetlinks.json"
else
  echo "NOT THE PERSONAL CERTIFICATE: do not install this APK over the existing app (Android would refuse the update, and the App Link would not verify)."
  exit 2
fi
