#!/usr/bin/env bash
# Build, sign and publish an Android app release from this machine.
#
#   tools/release.sh X.Y.Z ["one-line release note"]
#
# Per-app settings live in tools/release.conf (APP_NAME, REPO, ASSET, PREFIX,
# GRADLE_VERSION_PROP, CERT_SHA256, KEY_ENV, ALLOW_INTERNET). The release key
# never leaves this machine, so a compromised GitHub account or workflow can't
# sign an update. The script runs only on a clean `main` in sync with origin,
# then:
#   1. runs the unit tests and lint,
#   2. builds the release with versionName X.Y.Z and versionCode X*10^6+Y*10^3+Z,
#   3. verifies the signing certificate against the pinned SHA-256, the
#      version, and (unless ALLOW_INTERNET=1) that there's no INTERNET permission,
#   4. creates tag vX.Y.Z and a GitHub release with the APK, its .sha256 and
#      INSTALL.txt.
# Never run this under `bash -x`: it sources the signing env.
set -euo pipefail

die() { printf '✖ %s\n' "$*" >&2; exit 1; }
say() { printf '▸ %s\n' "$*"; }

root="${RELEASE_REPO_DIR:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$root"
conf="${RELEASE_CONF:-tools/release.conf}"
[[ -r "$conf" ]] || die "missing $conf"
# shellcheck source=/dev/null
. "$conf"
: "${APP_NAME:?}" "${REPO:?}" "${ASSET:?}" "${PREFIX:?}" "${GRADLE_VERSION_PROP:?}" "${CERT_SHA256:?}" "${KEY_ENV:?}"
ALLOW_INTERNET="${ALLOW_INTERNET:-0}"
SDK="${ANDROID_HOME:-$HOME/android}"
BUILD_TOOLS="$SDK/build-tools/36.0.0"

version="${1:-}"; note="${2:-}"
[[ "$version" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]] || die "usage: tools/release.sh X.Y.Z [note]"
major=$((10#${BASH_REMATCH[1]})); minor=$((10#${BASH_REMATCH[2]})); patch=$((10#${BASH_REMATCH[3]}))
(( minor <= 999 && patch <= 999 )) || die "MINOR and PATCH must be at most 999"
code=$((major * 1000000 + minor * 1000 + patch))
tag="v$version"
changelog="fastlane/metadata/android/en-US/changelogs/$code.txt"
[[ -n "$note" || -f "$changelog" ]] || die "give a release note or add $changelog"

[[ "$(git branch --show-current)" == main ]] || die "release from main only"
git fetch -q origin
[[ -z "$(git status --porcelain)" ]] || die "working tree is not clean"
[[ "$(git rev-parse HEAD)" == "$(git rev-parse origin/main)" ]] || die "main is not in sync with origin/main"
git rev-parse -q --verify "refs/tags/$tag" >/dev/null && die "tag $tag already exists"
[[ -r "$KEY_ENV" ]] || die "signing env not found: $KEY_ENV"

say "Checks…"
if [[ -d rust ]]; then
  (cd rust && cargo fmt --check && cargo clippy --all-targets --locked -q -- -D warnings && cargo test --locked -q >/dev/null)
fi
./gradlew -q testDebugUnitTest lint

say "Building signed $APP_NAME $tag (versionCode $code)…"
set -a
# shellcheck source=/dev/null
. "$KEY_ENV"
set +a
./gradlew -q -P"${GRADLE_VERSION_PROP}Name=$version" -P"${GRADLE_VERSION_PROP}Code=$code" clean assembleRelease
apk=app/build/outputs/apk/release/app-release.apk
[[ -f "$apk" ]] || die "no signed APK produced (signing env incomplete?)"

# Capture whole outputs before slicing: with pipefail, `tool | head -1` can
# kill the tool with SIGPIPE and abort silently.
signers=$("$BUILD_TOOLS/apksigner" verify --print-certs "$apk")
cert=$(sed -n 's/.*certificate SHA-256 digest: //p' <<<"$signers"); cert=${cert%%$'\n'*}
[[ "$cert" == "$CERT_SHA256" ]] || die "signed with an unexpected certificate: $cert"
perms=$("$BUILD_TOOLS/aapt2" dump permissions "$apk")
if [[ "$ALLOW_INTERNET" != 1 ]] && grep -q 'android.permission.INTERNET' <<<"$perms"; then
  die "the APK requests INTERNET"
fi
badging=$("$BUILD_TOOLS/aapt2" dump badging "$apk"); badging=${badging%%$'\n'*}
[[ "$badging" == *"versionCode='$code'"* && "$badging" == *"versionName='$version'"* ]] || die "unexpected version: $badging"
say "Certificate, permissions and version verified."

dist=$(mktemp -d)
trap 'rm -rf "$dist"' EXIT
cp "$apk" "$dist/$ASSET"
(cd "$dist" && sha256sum "$ASSET" > "$ASSET.sha256")
sha=$(cut -d' ' -f1 "$dist/$ASSET.sha256")
cat > "$dist/INSTALL.txt" <<EOF
$APP_NAME $version for Android (11+)

Easiest: add the Cybercore F-Droid repository and install from there:
  https://cybercoretech.github.io/fdroid/

Manual: download $ASSET on the phone and open it, or from a computer:
  adb install -r $ASSET
Updates: Obtainium with https://github.com/$REPO

Verify:  sha256sum --check $ASSET.sha256
Signing certificate SHA-256: $CERT_SHA256
EOF
{
  echo "## $APP_NAME $version"
  echo
  if [[ -f "$changelog" ]]; then sed 's/^/> /' "$changelog"; else echo "> $note"; fi
  echo
  echo "Install from the [Cybercore F-Droid repository](https://cybercoretech.github.io/fdroid/), Obtainium, or download \`$ASSET\` below."
  echo
  echo "- APK SHA-256: \`$sha\`"
  echo "- Signing certificate SHA-256: \`$CERT_SHA256\`"
  echo "- Signed on the maintainer's machine; the release key is never uploaded to GitHub."
} > "$dist/RELEASE_NOTES.md"

say "Tagging $tag and publishing…"
git tag -a "$tag" -m "$APP_NAME $version"
git push -q origin "$tag"
gh release create "$tag" -R "$REPO" \
  "$dist/$ASSET" "$dist/$ASSET.sha256" "$dist/INSTALL.txt" \
  --title "$APP_NAME $version" --notes-file "$dist/RELEASE_NOTES.md" --verify-tag
say "Released $tag: https://github.com/$REPO/releases/tag/$tag"
