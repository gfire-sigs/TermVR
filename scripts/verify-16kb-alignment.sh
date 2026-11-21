#!/usr/bin/env bash
# Verify that every ELF shared library aligns all PT_LOAD segments to at least 16384 bytes.

set -euo pipefail

CALLER_PWD="$(pwd)"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
LOCKFILE="${REPO_ROOT}/scripts/source-lock.json"

READELF_BIN="${LLVM_READELF:-}"
TARGETS=()

usage() {
  cat <<'EOF'
Usage: verify-16kb-alignment.sh [--readelf <path>] <so-file|directory|apk-file|aab-file|apks-file>...

Inspect every ELF .so target with llvm-readelf and fail if zero .so files are
found, zero LOAD segments are parsed, or any LOAD segment alignment is below
16384 bytes (0x4000).
EOF
}

log_info() {
  printf '[verify-16kb] %s\n' "$*"
}

die() {
  printf '[verify-16kb] ERROR: %s\n' "$*" >&2
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

pinned_ndk_version() {
  if [ -f "${LOCKFILE}" ] && command -v jq >/dev/null 2>&1; then
    jq -r '.toolchains.android_ndk // empty' "${LOCKFILE}" 2>/dev/null || true
  elif [ -f "${LOCKFILE}" ] && command -v python3 >/dev/null 2>&1; then
    python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("toolchains",{}).get("android_ndk",""))' "${LOCKFILE}" 2>/dev/null || true
  fi
}

probe_ndk_readelf_in_root() {
  local ndk_root="$1"
  [ -n "${ndk_root}" ] && [ -d "${ndk_root}/toolchains/llvm/prebuilt" ] || return 1
  local candidate
  for candidate in "${ndk_root}"/toolchains/llvm/prebuilt/*/bin/llvm-readelf; do
    if [ -x "${candidate}" ]; then
      printf '%s\n' "${candidate}"
      return 0
    fi
  done
  return 1
}

locate_llvm_readelf() {
  if [ -n "${READELF_BIN}" ]; then
    [ -x "${READELF_BIN}" ] || die "Specified llvm-readelf is not executable: ${READELF_BIN}"
    printf '%s\n' "${READELF_BIN}"
    return 0
  fi

  if command -v llvm-readelf >/dev/null 2>&1; then
    command -v llvm-readelf
    return 0
  fi

  local direct_ndk
  for direct_ndk in "${ANDROID_NDK_HOME:-}" "${ANDROID_NDK_ROOT:-}" "${NDK_HOME:-}"; do
    if [ -n "${direct_ndk}" ] && probe_ndk_readelf_in_root "${direct_ndk}"; then
      return 0
    fi
  done

  local pinned_ver
  pinned_ver="$(pinned_ndk_version)"

  local sdk_root
  for sdk_root in \
    "${ANDROID_HOME:-}" \
    "${ANDROID_SDK_ROOT:-}" \
    "${HOME:-}/Library/Android/sdk" \
    "${HOME:-}/Android/Sdk" \
    "/usr/lib/android-sdk" \
    "/opt/android-sdk"
  do
    [ -n "${sdk_root}" ] && [ -d "${sdk_root}/ndk" ] || continue
    if [ -n "${pinned_ver}" ] && probe_ndk_readelf_in_root "${sdk_root}/ndk/${pinned_ver}"; then
      return 0
    fi
    local ndk_subdir
    for ndk_subdir in "${sdk_root}"/ndk/*; do
      if probe_ndk_readelf_in_root "${ndk_subdir}"; then
        return 0
      fi
    done
  done

  die "Unable to locate llvm-readelf on PATH or inside Android NDK prebuilts"
}

check_so_file() {
  local readelf_exe="$1"
  local so_file="$2"
  local display_name="$3"

  [ -s "${so_file}" ] || die "Empty or missing shared library: ${display_name}"

  local readelf_out
  if ! readelf_out="$("${readelf_exe}" -W -l "${so_file}" 2>&1)"; then
    printf '%s\n' "${readelf_out}" >&2
    die "llvm-readelf failed on ${display_name}"
  fi

  if ! printf '%s\n' "${readelf_out}" | awk -v label="${display_name}" '
    function parse_align(raw,    val, idx, ch, pos) {
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", raw)
      if (raw ~ /^0[xX][0-9a-fA-F]+$/) {
        val = 0
        for (idx = 3; idx <= length(raw); idx++) {
          ch = tolower(substr(raw, idx, 1))
          pos = index("0123456789abcdef", ch)
          if (pos == 0) {
            return -1
          }
          val = (val * 16) + (pos - 1)
        }
        return val
      }
      if (raw ~ /^[0-9]+$/) {
        return raw + 0
      }
      return -1
    }
    $1 == "LOAD" {
      count++
      align = parse_align($NF)
      if (align < 16384) {
        bad = 1
        printf("[verify-16kb] FAIL: %s LOAD segment %d align=%s (%d < 16384)\n", label, count, $NF, align) > "/dev/stderr"
      }
    }
    END {
      if (count == 0) {
        printf("[verify-16kb] FAIL: %s contains zero LOAD segments\n", label) > "/dev/stderr"
        exit 1
      }
      if (bad) {
        exit 1
      }
    }
  '; then
    return 1
  fi

  log_info "PASS: ${display_name} (all LOAD segments >= 16384 bytes)"
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --readelf)
      [ "$#" -ge 2 ] || die "--readelf requires an executable path"
      READELF_BIN="$(resolve_abs_path "${CALLER_PWD}" "$2")"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    --)
      shift
      while [ "$#" -gt 0 ]; do
        TARGETS+=("$(resolve_abs_path "${CALLER_PWD}" "$1")")
        shift
      done
      ;;
    -*)
      die "Unknown option: $1"
      ;;
    *)
      TARGETS+=("$(resolve_abs_path "${CALLER_PWD}" "$1")")
      shift
      ;;
  esac
done

if [ "${#TARGETS[@]}" -eq 0 ]; then
  usage >&2
  die "Provide at least one .so file, directory, or .apk file"
fi

RESOLVED_READELF="$(locate_llvm_readelf)"
log_info "Using llvm-readelf: ${RESOLVED_READELF}"

TMP_EXTRACT_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/termvr-16kb.XXXXXX")"
trap 'rm -rf "${TMP_EXTRACT_ROOT}"' EXIT

TOTAL_CHECKED=0
FAILURES=0
APK_INDEX=0

for target in "${TARGETS[@]}"; do
  if [ ! -e "${target}" ]; then
    die "Target does not exist: ${target}"
  fi

  if [ -d "${target}" ]; then
    DIR_COUNT=0
    while IFS= read -r -d '' so_entry; do
      DIR_COUNT=$((DIR_COUNT + 1))
      TOTAL_CHECKED=$((TOTAL_CHECKED + 1))
      if ! check_so_file "${RESOLVED_READELF}" "${so_entry}" "${so_entry}"; then
        FAILURES=$((FAILURES + 1))
      fi
    done < <(find "${target}" -type f -name '*.so' -print0 | LC_ALL=C sort -z)
    if [ "${DIR_COUNT}" -eq 0 ]; then
      die "Directory contains zero .so files: ${target}"
    fi
  elif [ -f "${target}" ]; then
    case "${target}" in
      *.apk|*.aab)
        APK_INDEX=$((APK_INDEX + 1))
        APK_OUT="${TMP_EXTRACT_ROOT}/archive-${APK_INDEX}"
        mkdir -p "${APK_OUT}"
        if command -v unzip >/dev/null 2>&1; then
          unzip -q "${target}" 'lib/*.so' '*/lib/*.so' -d "${APK_OUT}" 2>/dev/null || true
        elif command -v python3 >/dev/null 2>&1; then
          python3 - "${target}" "${APK_OUT}" <<'PY'
import os
import sys
import zipfile

archive_path, out_dir = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(archive_path, "r") as archive:
    for member in archive.namelist():
        if (member.startswith("lib/") or "/lib/" in member) and member.endswith(".so"):
            dest = os.path.realpath(os.path.join(out_dir, member))
            if not dest.startswith(os.path.realpath(out_dir) + os.sep):
                raise RuntimeError(f"Unsafe zip member: {member}")
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            with archive.open(member) as src, open(dest, "wb") as dst:
                dst.write(src.read())
PY
        else
          die "Either unzip or python3 is required to inspect APK/AAB archives"
        fi

        APK_SO_COUNT=0
        while IFS= read -r -d '' apk_so; do
          APK_SO_COUNT=$((APK_SO_COUNT + 1))
          TOTAL_CHECKED=$((TOTAL_CHECKED + 1))
          REL_IN_APK="${apk_so#${APK_OUT}/}"
          if ! check_so_file "${RESOLVED_READELF}" "${apk_so}" "${target}!/${REL_IN_APK}"; then
            FAILURES=$((FAILURES + 1))
          fi
        done < <(find "${APK_OUT}" -type f -name '*.so' -print0 | LC_ALL=C sort -z)

        if [ "${APK_SO_COUNT}" -eq 0 ]; then
          die "Archive contains zero lib/**/*.so native libraries: ${target}"
        fi
        ;;
      *.apks)
        APK_INDEX=$((APK_INDEX + 1))
        APKS_OUT="${TMP_EXTRACT_ROOT}/apks-${APK_INDEX}"
        mkdir -p "${APKS_OUT}/nested" "${APKS_OUT}/so"
        python3 - "${target}" "${APKS_OUT}/nested" "${APKS_OUT}/so" <<'PY'
import os
import sys
import zipfile

apks_path, nested_dir, so_dir = sys.argv[1], sys.argv[2], sys.argv[3]
with zipfile.ZipFile(apks_path, "r") as outer:
    apk_members = [m for m in outer.namelist() if m.endswith(".apk")]
    if not apk_members:
        raise RuntimeError(f"APKS archive contains zero .apk splits: {apks_path}")
    for member in apk_members:
        nested_dest = os.path.realpath(os.path.join(nested_dir, member))
        if not nested_dest.startswith(os.path.realpath(nested_dir) + os.sep):
            raise RuntimeError(f"Unsafe zip member: {member}")
        os.makedirs(os.path.dirname(nested_dest), exist_ok=True)
        with outer.open(member) as src, open(nested_dest, "wb") as dst:
            dst.write(src.read())
        split_label = member.replace("/", "_")
        with zipfile.ZipFile(nested_dest, "r") as inner:
            for inner_member in inner.namelist():
                if (inner_member.startswith("lib/") or "/lib/" in inner_member) and inner_member.endswith(".so"):
                    so_dest = os.path.realpath(os.path.join(so_dir, split_label, inner_member))
                    if not so_dest.startswith(os.path.realpath(so_dir) + os.sep):
                        raise RuntimeError(f"Unsafe nested zip member: {inner_member}")
                    os.makedirs(os.path.dirname(so_dest), exist_ok=True)
                    with inner.open(inner_member) as src, open(so_dest, "wb") as dst:
                        dst.write(src.read())
PY
        APKS_SO_COUNT=0
        while IFS= read -r -d '' apks_so; do
          APKS_SO_COUNT=$((APKS_SO_COUNT + 1))
          TOTAL_CHECKED=$((TOTAL_CHECKED + 1))
          REL_IN_APKS="${apks_so#${APKS_OUT}/so/}"
          if ! check_so_file "${RESOLVED_READELF}" "${apks_so}" "${target}!/${REL_IN_APKS}"; then
            FAILURES=$((FAILURES + 1))
          fi
        done < <(find "${APKS_OUT}/so" -type f -name '*.so' -print0 | LC_ALL=C sort -z)

        if [ "${APKS_SO_COUNT}" -eq 0 ]; then
          die "APKS archive contains zero lib/**/*.so native libraries: ${target}"
        fi
        ;;
      *)
        TOTAL_CHECKED=$((TOTAL_CHECKED + 1))
        if ! check_so_file "${RESOLVED_READELF}" "${target}" "${target}"; then
          FAILURES=$((FAILURES + 1))
        fi
        ;;
    esac
  else
    die "Unsupported file type: ${target}"
  fi
done

if [ "${TOTAL_CHECKED}" -eq 0 ]; then
  die "Zero .so files were checked"
fi

if [ "${FAILURES}" -gt 0 ]; then
  die "${FAILURES} of ${TOTAL_CHECKED} shared libraries failed the 16 KB alignment check"
fi

log_info "Verified ${TOTAL_CHECKED} shared library file(s); all PT_LOAD segments align to >= 16384 bytes"
