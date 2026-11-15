#!/usr/bin/env bash
# Verify bit-for-bit APK reproducibility using SHA-256 comparison and apksigcopier.

set -euo pipefail

CALLER_PWD="$(pwd)"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

MODE="compare"
FIRST_APK=""
SECOND_APK=""
SIGNED_APK=""
ABI="arm64-v8a"
WORK_DIR=""
KEEP_WORK=0

usage() {
  cat <<'EOF'
Usage:
  verify-reproducible-apk.sh --unsigned-a <apk> --unsigned-b <apk>
  verify-reproducible-apk.sh --signed-apk <apk> [--abi <abi>] [--work-dir <dir>]
  verify-reproducible-apk.sh <unsigned-apk-a> <unsigned-apk-b>

Compare two unsigned APKs by SHA-256, or rebuild an unsigned APK in a clean
directory, transfer the v2/v3 signing block from a signed release APK via
apksigcopier, and assert an exact SHA-256 match. Runs diffoscope on mismatch.
EOF
}

log_info() {
  printf '[verify-repro] %s\n' "$*"
}

die() {
  printf '[verify-repro] ERROR: %s\n' "$*" >&2
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

compute_sha256() {
  local target_file="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "${target_file}" | awk '{print tolower($1)}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "${target_file}" | awk '{print tolower($1)}'
  elif command -v openssl >/dev/null 2>&1; then
    openssl dgst -sha256 "${target_file}" | awk '{print tolower($NF)}'
  else
    die "No SHA-256 tool found"
  fi
}

report_mismatch_with_diffoscope() {
  local left_apk="$1"
  local right_apk="$2"
  if command -v diffoscope >/dev/null 2>&1; then
    log_info "Running diffoscope on ${left_apk} vs ${right_apk}"
    diffoscope "${left_apk}" "${right_apk}" || true
  else
    log_info "diffoscope not installed; skipping structural diff"
  fi
}

compare_exact_sha256() {
  local left_apk="$1"
  local right_apk="$2"

  [ -f "${left_apk}" ] || die "Missing APK: ${left_apk}"
  [ -f "${right_apk}" ] || die "Missing APK: ${right_apk}"

  local left_sha
  local right_sha
  left_sha="$(compute_sha256 "${left_apk}")"
  right_sha="$(compute_sha256 "${right_apk}")"

  log_info "SHA-256 (${left_apk}):  ${left_sha}"
  log_info "SHA-256 (${right_apk}): ${right_sha}"

  if [ "${left_sha}" != "${right_sha}" ]; then
    report_mismatch_with_diffoscope "${left_apk}" "${right_apk}"
    die "APK reproducibility mismatch: ${left_sha} != ${right_sha}"
  fi

  log_info "PASS: exact SHA-256 match (${left_sha})"
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --unsigned-a)
      [ "$#" -ge 2 ] || die "--unsigned-a requires a file path"
      FIRST_APK="$(resolve_abs_path "${CALLER_PWD}" "$2")"
      MODE="compare"
      shift 2
      ;;
    --unsigned-b)
      [ "$#" -ge 2 ] || die "--unsigned-b requires a file path"
      SECOND_APK="$(resolve_abs_path "${CALLER_PWD}" "$2")"
      MODE="compare"
      shift 2
      ;;
    --signed-apk)
      [ "$#" -ge 2 ] || die "--signed-apk requires a file path"
      SIGNED_APK="$(resolve_abs_path "${CALLER_PWD}" "$2")"
      MODE="rebuild-signed"
      shift 2
      ;;
    --abi)
      [ "$#" -ge 2 ] || die "--abi requires an ABI value"
      ABI="$2"
      shift 2
      ;;
    --work-dir)
      [ "$#" -ge 2 ] || die "--work-dir requires a directory path"
      WORK_DIR="$(resolve_abs_path "${CALLER_PWD}" "$2")"
      KEEP_WORK=1
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    -*)
      die "Unknown option: $1"
      ;;
    *)
      if [ -z "${FIRST_APK}" ]; then
        FIRST_APK="$(resolve_abs_path "${CALLER_PWD}" "$1")"
      elif [ -z "${SECOND_APK}" ]; then
        SECOND_APK="$(resolve_abs_path "${CALLER_PWD}" "$1")"
      else
        die "Unexpected positional argument: $1"
      fi
      shift
      ;;
  esac
done

if [ "${MODE}" = "compare" ]; then
  if [ -z "${FIRST_APK}" ] || [ -z "${SECOND_APK}" ]; then
    usage >&2
    die "Provide two unsigned APKs or pass --signed-apk"
  fi
  compare_exact_sha256 "${FIRST_APK}" "${SECOND_APK}"
  exit 0
fi

[ -f "${SIGNED_APK}" ] || die "Missing signed APK: ${SIGNED_APK}"
command -v apksigcopier >/dev/null 2>&1 || die "apksigcopier is required for --signed-apk verification"
command -v git >/dev/null 2>&1 || die "git is required for clean-room rebuild"

if [ -z "${WORK_DIR}" ]; then
  WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/termvr-repro.XXXXXX")"
fi
cleanup() {
  if [ "${KEEP_WORK}" = "0" ] && [ -n "${WORK_DIR}" ]; then
    rm -rf "${WORK_DIR}"
  fi
}
trap cleanup EXIT

CLEAN_REPO="${WORK_DIR}/src"
mkdir -p "${CLEAN_REPO}"
git -C "${REPO_ROOT}" archive --format=tar HEAD | tar -xf - -C "${CLEAN_REPO}"

if [ -d "${REPO_ROOT}/.toolchains" ] && [ ! -e "${CLEAN_REPO}/.toolchains" ]; then
  ln -s "${REPO_ROOT}/.toolchains" "${CLEAN_REPO}/.toolchains"
fi

GRADLE_CMD="${GRADLE_BIN:-}"
if [ -z "${GRADLE_CMD}" ]; then
  if [ -x "${REPO_ROOT}/gradlew" ]; then
    GRADLE_CMD="${REPO_ROOT}/gradlew"
  elif command -v gradle >/dev/null 2>&1; then
    GRADLE_CMD="$(command -v gradle)"
  else
    die "Neither gradlew nor gradle was found on PATH"
  fi
fi

log_info "Rebuilding unsigned release APK for ${ABI} inside ${CLEAN_REPO}"
(
  cd "${CLEAN_REPO}"
  ./scripts/build-native-deps.sh --abi "${ABI}"
  "${GRADLE_CMD}" --no-daemon :app:assembleRelease "-PtermvrAbis=${ABI}"
)

REBUILT_UNSIGNED="$(find "${CLEAN_REPO}/app/build/outputs/apk/release" -type f -name '*.apk' | head -n 1)"
[ -n "${REBUILT_UNSIGNED}" ] || die "Clean-room build produced no APK under ${CLEAN_REPO}/app/build/outputs/apk/release"

COPIED_SIGNED="${WORK_DIR}/rebuilt-signed-${ABI}.apk"
apksigcopier copy "${SIGNED_APK}" "${REBUILT_UNSIGNED}" "${COPIED_SIGNED}"
compare_exact_sha256 "${SIGNED_APK}" "${COPIED_SIGNED}"
