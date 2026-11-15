#!/usr/bin/env bash
# Provision pinned Go and Zig toolchains, verify upstream commits, and populate offline caches.

set -euo pipefail

CALLER_PWD="$(pwd)"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
LOCKFILE="${REPO_ROOT}/scripts/source-lock.json"

TOOLCHAINS_DIR="${TERMVR_TOOLCHAINS_DIR:-${REPO_ROOT}/.toolchains}"
ALLOW_SYSTEM_TOOLCHAINS="${TERMVR_ALLOW_SYSTEM_TOOLCHAINS:-0}"
TOOLCHAINS_ONLY=0
ALL_SOURCES=0
ANY_SOURCE_FLAG=0

GVX_DIR=""
GHOSTTY_DIR=""
FREETYPE_DIR=""
HARFBUZZ_DIR=""
JETBRAINS_MONO_DIR=""
NERD_FONTS_DIR=""

usage() {
  cat <<'EOF'
Usage: provision-toolchains-and-vendor.sh [options]

Verify upstream Git commits against scripts/source-lock.json, install SHA-256
verified Go and Zig toolchains under .toolchains/, vendor gvx Go modules, and
populate the Ghostty Zig package cache for offline builds.

Options:
  --gvx <dir>                Path to gvx checkout
  --ghostty <dir>            Path to ghostty checkout
  --freetype <dir>           Path to freetype checkout
  --harfbuzz <dir>           Path to harfbuzz checkout
  --jetbrains-mono <dir>     Path to JetBrainsMono checkout
  --nerd-fonts <dir>         Path to nerd-fonts checkout
  --toolchains-dir <dir>     Cache directory (default: <repo>/.toolchains)
  --allow-system-toolchains  Permit matching system go and zig binaries
  --toolchains-only          Provision Go and Zig without source vendoring
  --all-sources              Clone any omitted upstream repositories
  -h, --help                 Print this help text
EOF
}

log_info() {
  printf '[provision] %s\n' "$*"
}

die() {
  printf '[provision] ERROR: %s\n' "$*" >&2
  exit 1
}

resolve_abs_path() {
  local base_dir="$1"
  local raw_path="$2"
  case "${raw_path}" in
    /*) printf '%s\n' "${raw_path}" ;;
    *) printf '%s/%s\n' "${base_dir}" "${raw_path}" ;;
  esac
}

require_existing_dir() {
  local label="$1"
  local path_val="$2"
  if [ ! -d "${path_val}" ]; then
    die "Directory for ${label} does not exist: ${path_val}"
  fi
  (cd "${path_val}" && pwd)
}

compute_sha256() {
  local target_file="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "${target_file}" | awk '{print tolower($1)}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "${target_file}" | awk '{print tolower($1)}'
  elif command -v openssl >/dev/null 2>&1; then
    openssl dgst -sha256 "${target_file}" | awk '{print tolower($NF)}'
  else
    die "No SHA-256 tool found (expected sha256sum, shasum, or openssl)"
  fi
}

verify_sha256() {
  local target_file="$1"
  local expected_hex
  expected_hex="$(printf '%s' "$2" | tr '[:upper:]' '[:lower:]')"
  local actual_hex
  actual_hex="$(compute_sha256 "${target_file}")"
  if [ "${actual_hex}" != "${expected_hex}" ]; then
    rm -f "${target_file}"
    die "SHA-256 mismatch on ${target_file}: expected ${expected_hex}, got ${actual_hex}"
  fi
}

json_query() {
  local expr="$1"
  if command -v jq >/dev/null 2>&1; then
    jq -er "${expr}" "${LOCKFILE}"
  elif command -v python3 >/dev/null 2>&1; then
    python3 - "${LOCKFILE}" "${expr}" <<'PY'
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as handle:
    data = json.load(handle)

parts = [segment for segment in sys.argv[2].lstrip(".").split(".") if segment]
cur = data
for part in parts:
    if not isinstance(cur, dict) or part not in cur:
        sys.exit(1)
    cur = cur[part]
if cur is None:
    sys.exit(1)
print(cur)
PY
  else
    die "Either jq or python3 is required to read ${LOCKFILE}"
  fi
}

validate_lockfile_json() {
  if [ ! -f "${LOCKFILE}" ]; then
    die "Missing source lockfile: ${LOCKFILE}"
  fi
  if command -v python3 >/dev/null 2>&1; then
    python3 -m json.tool "${LOCKFILE}" >/dev/null
  elif command -v jq >/dev/null 2>&1; then
    jq empty "${LOCKFILE}" >/dev/null
  else
    die "Either python3 or jq is required to validate ${LOCKFILE}"
  fi
}

detect_host_platform() {
  local uname_s
  local uname_m
  uname_s="$(uname -s)"
  uname_m="$(uname -m)"

  case "${uname_s}" in
    Linux)
      HOST_OS="linux"
      GO_OS="linux"
      ZIG_OS="linux"
      ;;
    Darwin)
      HOST_OS="darwin"
      GO_OS="darwin"
      ZIG_OS="macos"
      ;;
    *)
      die "Unsupported host operating system: ${uname_s}"
      ;;
  esac

  case "${uname_m}" in
    x86_64|amd64)
      HOST_ARCH="x86_64"
      GO_ARCH="amd64"
      ZIG_ARCH="x86_64"
      ;;
    aarch64|arm64)
      HOST_ARCH="aarch64"
      GO_ARCH="arm64"
      ZIG_ARCH="aarch64"
      ;;
    *)
      die "Unsupported host CPU architecture: ${uname_m}"
      ;;
  esac
}

expected_go_sha256() {
  local version="$1"
  local platform="$2"
  if [ -n "${TERMVR_GO_SHA256:-}" ]; then
    printf '%s\n' "${TERMVR_GO_SHA256}"
    return 0
  fi
  case "${version}:${platform}" in
    1.27.4:linux-amd64)
      printf '%s\n' "7226736c2339719cda28fbf3a54d71aa63bcefe376e98cef00034268dab24840"
      ;;
    1.27.4:linux-arm64)
      printf '%s\n' "3392582988a273a87ab5043ee1b2c9c17a2c3c00580bf9326c47a2e0aaaeac92"
      ;;
    1.27.4:darwin-arm64)
      printf '%s\n' "91a44a2b8a716600ad94a4f143e156116620db8828086a6e3602291be9bd0bb4"
      ;;
    1.27.4:darwin-amd64)
      printf '%s\n' "2b799377704ef12db39bd254995d5c348c9cc8d86562ca817724390e56c38f43"
      ;;
    *)
      die "No pinned SHA-256 recorded for Go ${version} on ${platform}"
      ;;
  esac
}

expected_zig_sha256() {
  local version="$1"
  local platform="$2"
  if [ -n "${TERMVR_ZIG_SHA256:-}" ]; then
    printf '%s\n' "${TERMVR_ZIG_SHA256}"
    return 0
  fi
  case "${version}:${platform}" in
    0.17.0:x86_64-linux)
      printf '%s\n' "1cbe9df9f27e6b78d14ccbca43b6703a404ef79ef1c463de901d7f088d4e2026"
      ;;
    0.17.0:aarch64-linux)
      printf '%s\n' "9e8d11661d4ae3bd57702a3832781e23ad151dde5798e16a5ccd503f65234ff8"
      ;;
    0.17.0:aarch64-macos)
      printf '%s\n' "b607e9b9234790a008116ae5bdb71c6243b84b9fb42a53a9e70fde41c06c536a"
      ;;
    0.17.0:x86_64-macos)
      printf '%s\n' "4f9a1c5269aa17ebda5e6d3c2b89d6cbf36f7d2b22a0306e9ab98f25f95529c6"
      ;;
    *)
      die "No pinned SHA-256 recorded for Zig ${version} on ${platform}"
      ;;
  esac
}

download_verified_archive() {
  local url="$1"
  local dest_file="$2"
  local expected_hash="$3"

  mkdir -p "$(dirname "${dest_file}")"
  if [ -f "${dest_file}" ]; then
    local cached_hash
    cached_hash="$(compute_sha256 "${dest_file}")"
    if [ "${cached_hash}" = "${expected_hash}" ]; then
      log_info "Reusing verified archive ${dest_file}"
      return 0
    fi
    rm -f "${dest_file}"
  fi

  if ! command -v curl >/dev/null 2>&1; then
    die "curl is required to download ${url}"
  fi

  local tmp_file="${dest_file}.tmp.$$"
  log_info "Downloading ${url}"
  curl --proto '=https' --tlsv1.2 -fsSL --retry 3 --connect-timeout 20 -o "${tmp_file}" "${url}"
  verify_sha256 "${tmp_file}" "${expected_hash}"
  mv -f "${tmp_file}" "${dest_file}"
}

provision_go() {
  local go_version="$1"
  local go_link="${TOOLCHAINS_DIR}/go"
  local go_slot="${TOOLCHAINS_DIR}/go-${go_version}-${HOST_OS}-${HOST_ARCH}"

  if [ -x "${go_link}/bin/go" ]; then
    local cached_ver
    cached_ver="$("${go_link}/bin/go" env GOVERSION 2>/dev/null || true)"
    if [ "${cached_ver}" = "go${go_version}" ]; then
      GO_BIN="${go_link}/bin/go"
      log_info "Using cached Go ${go_version} at ${GO_BIN}"
      return 0
    fi
  fi

  if [ "${ALLOW_SYSTEM_TOOLCHAINS}" = "1" ] && command -v go >/dev/null 2>&1; then
    local sys_go
    local sys_ver
    sys_go="$(command -v go)"
    sys_ver="$("${sys_go}" env GOVERSION 2>/dev/null || "${sys_go}" version | awk '{print $3}')"
    if [ "${sys_ver}" = "go${go_version}" ] || [ "${TERMVR_ALLOW_UNPINNED_SYSTEM_TOOLCHAINS:-0}" = "1" ]; then
      mkdir -p "${go_slot}/bin"
      ln -sfn "${sys_go}" "${go_slot}/bin/go"
      ln -sfn "${go_slot}" "${go_link}"
      GO_BIN="${go_link}/bin/go"
      log_info "Using system Go (${sys_ver}) via ${sys_go}"
      return 0
    fi
  fi

  local go_platform="${GO_OS}-${GO_ARCH}"
  local archive_name="go${go_version}.${go_platform}.tar.gz"
  local archive_path="${TOOLCHAINS_DIR}/downloads/${archive_name}"
  local go_url="${TERMVR_GO_MIRROR:-https://go.dev/dl}/${archive_name}"
  local go_sha256
  go_sha256="$(expected_go_sha256 "${go_version}" "${go_platform}")"

  download_verified_archive "${go_url}" "${archive_path}" "${go_sha256}"

  rm -rf "${go_slot}"
  mkdir -p "${go_slot}"
  tar -xzf "${archive_path}" -C "${go_slot}"
  ln -sfn "${go_slot}/go" "${go_link}"
  GO_BIN="${go_link}/bin/go"

  local installed_ver
  installed_ver="$("${GO_BIN}" env GOVERSION)"
  if [ "${installed_ver}" != "go${go_version}" ]; then
    die "Installed Go version ${installed_ver} does not match go${go_version}"
  fi
  log_info "Installed verified Go ${go_version} at ${GO_BIN}"
}

provision_zig() {
  local zig_version="$1"
  local zig_link="${TOOLCHAINS_DIR}/zig"
  local zig_slot="${TOOLCHAINS_DIR}/zig-${zig_version}-${HOST_OS}-${HOST_ARCH}"

  if [ -x "${zig_link}/zig" ]; then
    local cached_ver
    cached_ver="$("${zig_link}/zig" version 2>/dev/null || true)"
    if [ "${cached_ver}" = "${zig_version}" ]; then
      ZIG_BIN="${zig_link}/zig"
      log_info "Using cached Zig ${zig_version} at ${ZIG_BIN}"
      return 0
    fi
  fi

  if [ "${ALLOW_SYSTEM_TOOLCHAINS}" = "1" ] && command -v zig >/dev/null 2>&1; then
    local sys_zig
    local sys_ver
    sys_zig="$(command -v zig)"
    sys_ver="$("${sys_zig}" version 2>/dev/null || true)"
    if [ "${sys_ver}" = "${zig_version}" ] || [ "${TERMVR_ALLOW_UNPINNED_SYSTEM_TOOLCHAINS:-0}" = "1" ]; then
      mkdir -p "${zig_slot}"
      ln -sfn "${sys_zig}" "${zig_slot}/zig"
      ln -sfn "${zig_slot}" "${zig_link}"
      ZIG_BIN="${zig_link}/zig"
      log_info "Using system Zig (${sys_ver}) via ${sys_zig}"
      return 0
    fi
  fi

  local zig_platform="${ZIG_ARCH}-${ZIG_OS}"
  local stem="zig-${zig_platform}-${zig_version}"
  local archive_name="${stem}.tar.xz"
  local archive_path="${TOOLCHAINS_DIR}/downloads/${archive_name}"
  local zig_url="${TERMVR_ZIG_MIRROR:-https://ziglang.org/download/${zig_version}}/${archive_name}"
  local zig_sha256
  zig_sha256="$(expected_zig_sha256 "${zig_version}" "${zig_platform}")"

  download_verified_archive "${zig_url}" "${archive_path}" "${zig_sha256}"

  rm -rf "${zig_slot}"
  mkdir -p "${zig_slot}"
  tar -xJf "${archive_path}" -C "${zig_slot}" --strip-components=1
  ln -sfn "${zig_slot}" "${zig_link}"
  ZIG_BIN="${zig_link}/zig"

  local installed_ver
  installed_ver="$("${ZIG_BIN}" version)"
  if [ "${installed_ver}" != "${zig_version}" ]; then
    die "Installed Zig version ${installed_ver} does not match ${zig_version}"
  fi
  log_info "Installed verified Zig ${zig_version} at ${ZIG_BIN}"
}

verify_git_head() {
  local source_key="$1"
  local repo_dir="$2"
  local expected_commit
  expected_commit="$(json_query ".sources.${source_key}.commit")"

  if [ ! -d "${repo_dir}" ]; then
    die "Missing source directory for ${source_key}: ${repo_dir}"
  fi

  local actual_commit
  actual_commit="$(git -C "${repo_dir}" rev-parse HEAD 2>/dev/null || true)"
  if [ -z "${actual_commit}" ]; then
    die "Directory for ${source_key} is not a Git checkout: ${repo_dir}"
  fi
  if [ "${actual_commit}" != "${expected_commit}" ]; then
    die "Commit mismatch for ${source_key} in ${repo_dir}: expected ${expected_commit}, got ${actual_commit}"
  fi
  log_info "Verified ${source_key} HEAD ${actual_commit}"
}

clone_or_checkout_locked_source() {
  local source_key="$1"
  local target_dir="$2"
  local repo_url
  local expected_commit
  repo_url="$(json_query ".sources.${source_key}.repository")"
  expected_commit="$(json_query ".sources.${source_key}.commit")"

  if [ -d "${target_dir}/.git" ] || [ -f "${target_dir}/.git" ]; then
    local current_head
    current_head="$(git -C "${target_dir}" rev-parse HEAD 2>/dev/null || true)"
    if [ "${current_head}" = "${expected_commit}" ]; then
      log_info "Reusing ${source_key} checkout at ${expected_commit}"
      return 0
    fi
  fi

  rm -rf "${target_dir}"
  mkdir -p "${target_dir}"
  git -C "${target_dir}" init -q
  git -C "${target_dir}" remote add origin "${repo_url}"

  if [ "${source_key}" = "nerd_fonts_symbols" ]; then
    if git -C "${target_dir}" sparse-checkout init --no-cone >/dev/null 2>&1; then
      git -C "${target_dir}" sparse-checkout set \
        "/LICENSE" \
        "/README.md" \
        "/patched-fonts/NerdFontsSymbolsOnly/"
      git -C "${target_dir}" fetch --depth 1 --filter=blob:none origin "${expected_commit}"
    else
      git -C "${target_dir}" fetch --depth 1 origin "${expected_commit}"
    fi
  else
    git -C "${target_dir}" fetch --depth 1 origin "${expected_commit}"
  fi

  git -C "${target_dir}" checkout -q --force --detach FETCH_HEAD
  verify_git_head "${source_key}" "${target_dir}"
}

ensure_source_dir() {
  local source_key="$1"
  local current_dir="$2"
  local require_present="$3"

  if [ -n "${current_dir}" ]; then
    local resolved
    resolved="$(require_existing_dir "${source_key}" "${current_dir}")"
    verify_git_head "${source_key}" "${resolved}" >&2
    printf '%s\n' "${resolved}"
    return 0
  fi

  if [ "${require_present}" = "1" ]; then
    local default_dir="${TOOLCHAINS_DIR}/src/${source_key}"
    clone_or_checkout_locked_source "${source_key}" "${default_dir}" >&2
    printf '%s\n' "${default_dir}"
    return 0
  fi

  printf '%s\n' ""
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --gvx)
      [ "$#" -ge 2 ] || die "--gvx requires a directory argument"
      GVX_DIR="$(resolve_abs_path "${CALLER_PWD}" "$2")"
      ANY_SOURCE_FLAG=1
      shift 2
      ;;
    --ghostty)
      [ "$#" -ge 2 ] || die "--ghostty requires a directory argument"
      GHOSTTY_DIR="$(resolve_abs_path "${CALLER_PWD}" "$2")"
      ANY_SOURCE_FLAG=1
      shift 2
      ;;
    --freetype)
      [ "$#" -ge 2 ] || die "--freetype requires a directory argument"
      FREETYPE_DIR="$(resolve_abs_path "${CALLER_PWD}" "$2")"
      ANY_SOURCE_FLAG=1
      shift 2
      ;;
    --harfbuzz)
      [ "$#" -ge 2 ] || die "--harfbuzz requires a directory argument"
      HARFBUZZ_DIR="$(resolve_abs_path "${CALLER_PWD}" "$2")"
      ANY_SOURCE_FLAG=1
      shift 2
      ;;
    --jetbrains-mono|--jetbrains_mono)
      [ "$#" -ge 2 ] || die "$1 requires a directory argument"
      JETBRAINS_MONO_DIR="$(resolve_abs_path "${CALLER_PWD}" "$2")"
      ANY_SOURCE_FLAG=1
      shift 2
      ;;
    --nerd-fonts|--nerd-fonts-symbols|--nerd_fonts_symbols)
      [ "$#" -ge 2 ] || die "$1 requires a directory argument"
      NERD_FONTS_DIR="$(resolve_abs_path "${CALLER_PWD}" "$2")"
      ANY_SOURCE_FLAG=1
      shift 2
      ;;
    --toolchains-dir)
      [ "$#" -ge 2 ] || die "--toolchains-dir requires a directory argument"
      TOOLCHAINS_DIR="$(resolve_abs_path "${CALLER_PWD}" "$2")"
      shift 2
      ;;
    --allow-system-toolchains)
      ALLOW_SYSTEM_TOOLCHAINS=1
      shift
      ;;
    --toolchains-only)
      TOOLCHAINS_ONLY=1
      shift
      ;;
    --all-sources)
      ALL_SOURCES=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      die "Unknown option: $1"
      ;;
  esac
done

validate_lockfile_json
detect_host_platform

mkdir -p "${TOOLCHAINS_DIR}"
TOOLCHAINS_DIR="$(cd "${TOOLCHAINS_DIR}" && pwd)"

PINNED_GO_VERSION="$(json_query ".toolchains.go")"
PINNED_ZIG_VERSION="$(json_query ".toolchains.zig")"

provision_go "${PINNED_GO_VERSION}"
provision_zig "${PINNED_ZIG_VERSION}"

ZIG_GLOBAL_CACHE_DIR="${TOOLCHAINS_DIR}/zig-cache"
mkdir -p "${ZIG_GLOBAL_CACHE_DIR}/p"

if [ "${TOOLCHAINS_ONLY}" = "0" ]; then
  CLONE_OPTIONAL=0
  if [ "${ANY_SOURCE_FLAG}" = "0" ] || [ "${ALL_SOURCES}" = "1" ]; then
    CLONE_OPTIONAL=1
  fi

  GVX_DIR="$(ensure_source_dir "gvx" "${GVX_DIR}" "1")"
  GHOSTTY_DIR="$(ensure_source_dir "ghostty" "${GHOSTTY_DIR}" "1")"
  FREETYPE_DIR="$(ensure_source_dir "freetype" "${FREETYPE_DIR}" "${CLONE_OPTIONAL}")"
  HARFBUZZ_DIR="$(ensure_source_dir "harfbuzz" "${HARFBUZZ_DIR}" "${CLONE_OPTIONAL}")"
  JETBRAINS_MONO_DIR="$(ensure_source_dir "jetbrains_mono" "${JETBRAINS_MONO_DIR}" "${CLONE_OPTIONAL}")"
  NERD_FONTS_DIR="$(ensure_source_dir "nerd_fonts_symbols" "${NERD_FONTS_DIR}" "${CLONE_OPTIONAL}")"

  export PATH="$(dirname "${GO_BIN}"):$(dirname "${ZIG_BIN}"):${PATH}"
  export GOTOOLCHAIN=local
  export GOCACHE="${TOOLCHAINS_DIR}/go-build-cache"
  export GOMODCACHE="${TOOLCHAINS_DIR}/go-mod-cache"
  mkdir -p "${GOCACHE}" "${GOMODCACHE}"

  log_info "Vendoring Go modules in ${GVX_DIR}"
  (
    cd "${GVX_DIR}"
    "${GO_BIN}" mod vendor
  )
  if [ ! -f "${GVX_DIR}/vendor/modules.txt" ]; then
    die "Failed to generate ${GVX_DIR}/vendor/modules.txt"
  fi

  log_info "Populating Ghostty Zig package cache in ${ZIG_GLOBAL_CACHE_DIR}"
  if [ -f "${GHOSTTY_DIR}/nix/build-support/fetch-zig-cache.sh" ] && [ -f "${GHOSTTY_DIR}/deps/libghostty-vt.txt" ]; then
    ZIG_GLOBAL_CACHE_DIR="${ZIG_GLOBAL_CACHE_DIR}" \
      sh "${GHOSTTY_DIR}/nix/build-support/fetch-zig-cache.sh" "${GHOSTTY_DIR}/deps/libghostty-vt.txt"
  fi
  (
    cd "${GHOSTTY_DIR}"
    ZIG_GLOBAL_CACHE_DIR="${ZIG_GLOBAL_CACHE_DIR}" "${ZIG_BIN}" build --fetch
  )
fi

ZIG_SYSTEM_PACKAGE_DIR="${ZIG_GLOBAL_CACHE_DIR}/p"
if [ ! -d "${ZIG_SYSTEM_PACKAGE_DIR}" ]; then
  ZIG_SYSTEM_PACKAGE_DIR="${ZIG_GLOBAL_CACHE_DIR}"
fi

cat >"${TOOLCHAINS_DIR}/env.sh" <<EOF
export GO_BIN="${GO_BIN}"
export ZIG_BIN="${ZIG_BIN}"
export ZIG_GLOBAL_CACHE_DIR="${ZIG_GLOBAL_CACHE_DIR}"
export ZIG_SYSTEM_PACKAGE_DIR="${ZIG_SYSTEM_PACKAGE_DIR}"
export PATH="$(dirname "${GO_BIN}"):$(dirname "${ZIG_BIN}"):\${PATH}"
EOF

log_info "Provisioning finished cleanly (${TOOLCHAINS_DIR})"
