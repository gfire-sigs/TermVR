#!/usr/bin/env bash
# Build or update the self-hosted TermVR F-Droid repository for GitHub Pages.

set -euo pipefail
umask 077

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

APK_PATH=""
SITE_DIR=""
PREV_SITE_DIR=""
KEYSTORE_PATH=""
KEY_ALIAS=""
REPO_URL="https://gfire-sigs.github.io/TermVR/fdroid/repo"
CUSTOM_DOMAIN=""
ARCHIVE_OLDER="3"

usage() {
  cat <<'EOF'
Usage: publish-fdroid-repo.sh --apk <path> --site-dir <dir> --keystore <path> --key-alias <alias> [--prev-site-dir <dir>] [--repo-url <url>] [--custom-domain <host>]

Reads the keystore password from FDROID_REPO_KEYSTORE_PASS, updates the signed
F-Droid index (v1 and v2), syncs Fastlane metadata, and writes the static
GitHub Pages site into --site-dir.
EOF
}

die() {
  printf '[fdroid-repo] ERROR: %s\n' "$*" >&2
  exit 1
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --apk)
      APK_PATH="$2"
      shift 2
      ;;
    --site-dir)
      SITE_DIR="$2"
      shift 2
      ;;
    --prev-site-dir)
      PREV_SITE_DIR="$2"
      shift 2
      ;;
    --keystore)
      KEYSTORE_PATH="$2"
      shift 2
      ;;
    --key-alias)
      KEY_ALIAS="$2"
      shift 2
      ;;
    --repo-url)
      REPO_URL="$2"
      shift 2
      ;;
    --custom-domain)
      CUSTOM_DOMAIN="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      die "Unknown argument: $1"
      ;;
  esac
done

[ -f "${APK_PATH}" ] || die "Missing signed APK: ${APK_PATH}"
[ -n "${SITE_DIR}" ] || die "--site-dir is required"
[ -f "${KEYSTORE_PATH}" ] || die "Missing keystore: ${KEYSTORE_PATH}"
[ -n "${KEY_ALIAS}" ] || die "--key-alias is required"
[ -n "${FDROID_REPO_KEYSTORE_PASS:-}" ] || die "FDROID_REPO_KEYSTORE_PASS environment variable is required"

command -v fdroid >/dev/null 2>&1 || die "fdroidserver (fdroid) is required on PATH"
command -v keytool >/dev/null 2>&1 || die "keytool is required on PATH"
command -v openssl >/dev/null 2>&1 || die "openssl is required on PATH"

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/termvr-fdroid-repo.XXXXXX")"
trap 'rm -rf "${WORK_DIR}"' EXIT

mkdir -p "${WORK_DIR}/repo" "${WORK_DIR}/metadata/dev.gfire.termvr/en-US/phoneScreenshots" "${WORK_DIR}/metadata/dev.gfire.termvr/en-US/changelogs"

# Preserve existing APKs from previous gh-pages deployment for archive/rollback history.
if [ -n "${PREV_SITE_DIR}" ] && [ -d "${PREV_SITE_DIR}/fdroid/repo" ]; then
  find "${PREV_SITE_DIR}/fdroid/repo" -maxdepth 1 -type f -name '*.apk' -exec cp -f {} "${WORK_DIR}/repo/" \;
fi
if [ -n "${PREV_SITE_DIR}" ] && [ -d "${PREV_SITE_DIR}/fdroid/archive" ]; then
  mkdir -p "${WORK_DIR}/archive"
  find "${PREV_SITE_DIR}/fdroid/archive" -maxdepth 1 -type f -name '*.apk' -exec cp -f {} "${WORK_DIR}/archive/" \;
fi

cp -f "${APK_PATH}" "${WORK_DIR}/repo/$(basename "${APK_PATH}")"

# Sync app metadata and Fastlane assets into fdroidserver layout.
FASTLANE_EN="${REPO_ROOT}/fastlane/metadata/android/en-US"
cp -f "${FASTLANE_EN}/images/icon.png" "${WORK_DIR}/fdroid-icon.png"
cp -f "${FASTLANE_EN}/images/icon.png" "${WORK_DIR}/metadata/dev.gfire.termvr/en-US/icon.png"
if [ -f "${FASTLANE_EN}/images/featureGraphic.png" ]; then
  cp -f "${FASTLANE_EN}/images/featureGraphic.png" "${WORK_DIR}/metadata/dev.gfire.termvr/en-US/featureGraphic.png"
fi
if [ -d "${FASTLANE_EN}/images/phoneScreenshots" ]; then
  cp -f "${FASTLANE_EN}/images/phoneScreenshots/"*.png "${WORK_DIR}/metadata/dev.gfire.termvr/en-US/phoneScreenshots/"
fi
if [ -d "${FASTLANE_EN}/changelogs" ]; then
  cp -f "${FASTLANE_EN}/changelogs/"*.txt "${WORK_DIR}/metadata/dev.gfire.termvr/en-US/changelogs/"
fi
cp -f "${FASTLANE_EN}/short_description.txt" "${WORK_DIR}/metadata/dev.gfire.termvr/en-US/summary.txt"
cp -f "${FASTLANE_EN}/full_description.txt" "${WORK_DIR}/metadata/dev.gfire.termvr/en-US/description.txt"

cat > "${WORK_DIR}/metadata/dev.gfire.termvr.yml" <<'EOF'
Categories:
  - Development
  - System
License: Apache-2.0
AuthorName: gfire-sigs
WebSite: https://github.com/gfire-sigs/TermVR
SourceCode: https://github.com/gfire-sigs/TermVR
IssueTracker: https://github.com/gfire-sigs/TermVR/issues
Changelog: https://github.com/gfire-sigs/TermVR/blob/main/CHANGELOG.md
AutoName: TermVR
EOF

# Extract public SHA-256 fingerprint for landing page and repo URL.
REPO_FINGERPRINT="$(keytool -exportcert -rfc \
  -keystore "${KEYSTORE_PATH}" \
  -storetype PKCS12 \
  -alias "${KEY_ALIAS}" \
  -storepass:env FDROID_REPO_KEYSTORE_PASS 2>/dev/null \
  | openssl x509 -noout -fingerprint -sha256 \
  | sed 's/^.*=//;s/://g')"

cat > "${WORK_DIR}/config.yml" <<EOF
repo_url: ${REPO_URL}
repo_name: TermVR Official F-Droid Repo
repo_icon: fdroid-icon.png
repo_description: >-
  Official self-hosted F-Droid repository for TermVR, an unrooted Android 12+
  Linux container runtime powered by gvx and libghostty-vt.

archive_older: ${ARCHIVE_OLDER}
archive_url: ${REPO_URL%/repo}/archive
archive_name: TermVR Official F-Droid Archive
archive_icon: fdroid-icon.png
archive_description: >-
  Archived TermVR releases signed with the official TermVR release key.

keystore: ${KEYSTORE_PATH}
repo_keyalias: ${KEY_ALIAS}
keystorepass: {env: FDROID_REPO_KEYSTORE_PASS}
keypass: {env: FDROID_REPO_KEYSTORE_PASS}
EOF

chmod 600 "${WORK_DIR}/config.yml" "${KEYSTORE_PATH}"

(
  cd "${WORK_DIR}"
  fdroid update --verbose
)

# Publish only public repository output (never config.yml or keystore).
rm -rf "${SITE_DIR}"
mkdir -p "${SITE_DIR}/fdroid"
cp -a "${WORK_DIR}/repo" "${SITE_DIR}/fdroid/repo"
if [ -d "${WORK_DIR}/archive" ]; then
  cp -a "${WORK_DIR}/archive" "${SITE_DIR}/fdroid/archive"
fi
touch "${SITE_DIR}/.nojekyll"
if [ -n "${CUSTOM_DOMAIN}" ]; then
  printf '%s\n' "${CUSTOM_DOMAIN}" > "${SITE_DIR}/CNAME"
fi

FULL_REPO_URL="${REPO_URL}?fingerprint=${REPO_FINGERPRINT}"
FDROIDREPOS_URL="fdroidrepos://${REPO_URL#https://}?fingerprint=${REPO_FINGERPRINT}"

write_landing_page() {
  local target_file="$1"
  local qr_src="$2"
  cat > "${target_file}" <<EOF
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>TermVR Official F-Droid Repository</title>
  <style>
    body { font-family: system-ui, -apple-system, sans-serif; max-width: 760px; margin: 2.5rem auto; padding: 0 1.25rem; line-height: 1.6; color: #161b22; background: #ffffff; }
    code, pre { background: #f3f4f6; border-radius: 8px; padding: 0.2rem 0.45rem; word-break: break-all; }
    pre { padding: 0.9rem 1rem; overflow-x: auto; margin: 0.5rem 0 0.75rem; }
    a { color: #d9531e; }
    .actions { display: flex; flex-wrap: wrap; gap: 0.65rem; margin: 1.25rem 0; }
    .btn { display: inline-flex; align-items: center; justify-content: center; padding: 0.65rem 1.05rem; border-radius: 8px; font-weight: 600; font-size: 0.95rem; text-decoration: none; border: 1px solid #d0d7de; background: #f6f8fa; color: #161b22; cursor: pointer; }
    .btn-primary { background: #1976d2; border-color: #1976d2; color: #ffffff; }
    .btn-primary:hover { background: #1565c0; }
    .btn:hover { background: #eaeef2; }
    .qr-wrap { margin: 1.25rem 0; padding: 1rem; border: 1px solid #d0d7de; border-radius: 12px; display: inline-block; background: #ffffff; }
    .qr-wrap img { display: block; width: 200px; height: 200px; image-rendering: pixelated; }
    .status { font-size: 0.85rem; color: #1a7f37; font-weight: 600; min-height: 1.2em; }
  </style>
</head>
<body>
  <h1>TermVR Official F-Droid Repository</h1>
  <p>Add this repository in F-Droid, Droid-ify, or Neo Store to receive signed, reproducible <code>arm64-v8a</code> releases of TermVR automatically.</p>

  <div class="actions">
    <a class="btn btn-primary" href="${FDROIDREPOS_URL}">Open in F-Droid / Droid-ify / Neo Store</a>
    <button class="btn" type="button" onclick="copyText('repo-url', 'repo-status')">Copy Repo URL</button>
    <button class="btn" type="button" onclick="copyText('repo-fp', 'fp-status')">Copy Fingerprint</button>
  </div>

  <div class="qr-wrap">
    <img src="${qr_src}" alt="QR code for TermVR F-Droid repository">
  </div>

  <h2>Repository URL</h2>
  <pre id="repo-url">${FULL_REPO_URL}</pre>
  <div id="repo-status" class="status" role="status" aria-live="polite"></div>

  <h2>SHA-256 Fingerprint</h2>
  <pre id="repo-fp">${REPO_FINGERPRINT}</pre>
  <div id="fp-status" class="status" role="status" aria-live="polite"></div>

  <h2>Other install channels</h2>
  <ul>
    <li><a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/gfire-sigs/TermVR">Install with Obtainium (GitHub Releases)</a></li>
    <li><a href="https://github.com/gfire-sigs/TermVR/releases/latest">Download APK / APKS / AAB from GitHub Releases</a></li>
    <li><a href="https://github.com/gfire-sigs/TermVR/attestations">Verify SLSA Build Provenance Attestations</a></li>
  </ul>

  <script>
    async function copyText(elId, statusId) {
      const text = document.getElementById(elId).textContent.trim();
      const status = document.getElementById(statusId);
      try {
        await navigator.clipboard.writeText(text);
        status.textContent = 'Copied to clipboard.';
      } catch (err) {
        const range = document.createRange();
        range.selectNodeContents(document.getElementById(elId));
        const sel = window.getSelection();
        sel.removeAllRanges();
        sel.addRange(range);
        document.execCommand('copy');
        sel.removeAllRanges();
        status.textContent = 'Copied to clipboard.';
      }
      setTimeout(() => { status.textContent = ''; }, 2500);
    }
  </script>
</body>
</html>
EOF
}

write_landing_page "${SITE_DIR}/index.html" "fdroid/repo/index.png"
write_landing_page "${SITE_DIR}/fdroid/repo/index.html" "index.png"

chmod -R a+rX "${SITE_DIR}"
printf '[fdroid-repo] Built F-Droid repo at %s (fingerprint: %s)\n' "${SITE_DIR}/fdroid/repo" "${REPO_FINGERPRINT}"
