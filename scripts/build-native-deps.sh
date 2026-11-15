#!/usr/bin/env bash
# Compile offline native dependencies, static guest agent, and bundled fonts.

set -euo pipefail

CALLER_PWD="$(pwd)"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
LOCKFILE="${REPO_ROOT}/scripts/source-lock.json"

TOOLCHAINS_DIR="${TERMVR_TOOLCHAINS_DIR:-${REPO_ROOT}/.toolchains}"
ABIS_CSV="arm64-v8a,x86_64"
GVX_DIR=""
GHOSTTY_DIR=""
FREETYPE_DIR=""
HARFBUZZ_DIR=""
JETBRAINS_MONO_DIR=""
NERD_FONTS_DIR=""

usage() {
  cat <<'EOF'
Usage: build-native-deps.sh [options]

Build librungv.so, static termvr-agent, libghostty-vt.a, libfreetype.a, and
libharfbuzz.a completely offline, and stage headers and bundled fonts.

Options:
  --gvx <dir>             Path to vendored gvx checkout
  --ghostty <dir>         Path to ghostty checkout
  --freetype <dir>        Path to freetype checkout
  --harfbuzz <dir>        Path to harfbuzz checkout
  --jetbrains-mono <dir>  Path to JetBrainsMono checkout
  --nerd-fonts <dir>      Path to nerd-fonts checkout
  --abi <list>            Comma-separated ABIs: arm64-v8a, x86_64 (default: both)
  --toolchains-dir <dir>  Toolchain cache directory (default: <repo>/.toolchains)
  -h, --help              Print this help text
EOF
}

log_info() {
  printf '[build-native] %s\n' "$*"
}

die() {
  printf '[build-native] ERROR: %s\n' "$*" >&2
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

require_dir() {
  local label="$1"
  local dir_path="$2"
  if [ ! -d "${dir_path}" ]; then
    die "Missing ${label} directory: ${dir_path}"
  fi
  (cd "${dir_path}" && pwd)
}

resolve_go_bin() {
  if [ -n "${GO_BIN:-}" ] && [ -x "${GO_BIN}" ]; then
    printf '%s\n' "${GO_BIN}"
    return 0
  fi
  if [ -x "${TOOLCHAINS_DIR}/go/bin/go" ]; then
    printf '%s\n' "${TOOLCHAINS_DIR}/go/bin/go"
    return 0
  fi
  if command -v go >/dev/null 2>&1; then
    command -v go
    return 0
  fi
  die "Go toolchain not found. Run scripts/provision-toolchains-and-vendor.sh first."
}

resolve_zig_bin() {
  if [ -n "${ZIG_BIN:-}" ] && [ -x "${ZIG_BIN}" ]; then
    printf '%s\n' "${ZIG_BIN}"
    return 0
  fi
  if [ -x "${TOOLCHAINS_DIR}/zig/zig" ]; then
    printf '%s\n' "${TOOLCHAINS_DIR}/zig/zig"
    return 0
  fi
  if command -v zig >/dev/null 2>&1; then
    command -v zig
    return 0
  fi
  die "Zig toolchain not found. Run scripts/provision-toolchains-and-vendor.sh first."
}

locate_ndk_dir() {
  local pinned_ver="$1"
  local candidate
  for candidate in "${ANDROID_NDK_HOME:-}" "${ANDROID_NDK_ROOT:-}" "${NDK_HOME:-}"; do
    if [ -n "${candidate}" ] && [ -f "${candidate}/build/cmake/android.toolchain.cmake" ]; then
      (cd "${candidate}" && pwd)
      return 0
    fi
  done

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
    if [ -n "${pinned_ver}" ] && [ -f "${sdk_root}/ndk/${pinned_ver}/build/cmake/android.toolchain.cmake" ]; then
      (cd "${sdk_root}/ndk/${pinned_ver}" && pwd)
      return 0
    fi
    local any_ndk
    for any_ndk in "${sdk_root}"/ndk/*; do
      if [ -f "${any_ndk}/build/cmake/android.toolchain.cmake" ]; then
        (cd "${any_ndk}" && pwd)
        return 0
      fi
    done
  done

  die "Android NDK (expected ${pinned_ver}) not found. Set ANDROID_NDK_HOME or ANDROID_HOME."
}

locate_ndk_llvm_bin() {
  local ndk_dir="$1"
  local prebuilt_dir
  for prebuilt_dir in "${ndk_dir}"/toolchains/llvm/prebuilt/*/bin; do
    if [ -d "${prebuilt_dir}" ]; then
      printf '%s\n' "${prebuilt_dir}"
      return 0
    fi
  done
  die "Unable to locate LLVM prebuilt bin directory inside NDK: ${ndk_dir}"
}

detect_cpu_count() {
  if command -v nproc >/dev/null 2>&1; then
    nproc
  elif command -v sysctl >/dev/null 2>&1; then
    sysctl -n hw.ncpu
  else
    printf '4\n'
  fi
}

file_size_bytes() {
  local target_file="$1"
  if stat -c%s "${target_file}" >/dev/null 2>&1; then
    stat -c%s "${target_file}"
  else
    stat -f%z "${target_file}"
  fi
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --gvx)
      [ "$#" -ge 2 ] || die "--gvx requires a directory argument"
      GVX_DIR="$(resolve_abs_path "${CALLER_PWD}" "$2")"
      shift 2
      ;;
    --ghostty)
      [ "$#" -ge 2 ] || die "--ghostty requires a directory argument"
      GHOSTTY_DIR="$(resolve_abs_path "${CALLER_PWD}" "$2")"
      shift 2
      ;;
    --freetype)
      [ "$#" -ge 2 ] || die "--freetype requires a directory argument"
      FREETYPE_DIR="$(resolve_abs_path "${CALLER_PWD}" "$2")"
      shift 2
      ;;
    --harfbuzz)
      [ "$#" -ge 2 ] || die "--harfbuzz requires a directory argument"
      HARFBUZZ_DIR="$(resolve_abs_path "${CALLER_PWD}" "$2")"
      shift 2
      ;;
    --jetbrains-mono|--jetbrains_mono)
      [ "$#" -ge 2 ] || die "$1 requires a directory argument"
      JETBRAINS_MONO_DIR="$(resolve_abs_path "${CALLER_PWD}" "$2")"
      shift 2
      ;;
    --nerd-fonts|--nerd-fonts-symbols|--nerd_fonts_symbols)
      [ "$#" -ge 2 ] || die "$1 requires a directory argument"
      NERD_FONTS_DIR="$(resolve_abs_path "${CALLER_PWD}" "$2")"
      shift 2
      ;;
    --abi)
      [ "$#" -ge 2 ] || die "--abi requires an ABI argument"
      ABIS_CSV="$2"
      shift 2
      ;;
    --toolchains-dir)
      [ "$#" -ge 2 ] || die "--toolchains-dir requires a directory argument"
      TOOLCHAINS_DIR="$(resolve_abs_path "${CALLER_PWD}" "$2")"
      shift 2
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

[ -n "${GVX_DIR}" ] || GVX_DIR="${TOOLCHAINS_DIR}/src/gvx"
[ -n "${GHOSTTY_DIR}" ] || GHOSTTY_DIR="${TOOLCHAINS_DIR}/src/ghostty"
[ -n "${FREETYPE_DIR}" ] || FREETYPE_DIR="${TOOLCHAINS_DIR}/src/freetype"
[ -n "${HARFBUZZ_DIR}" ] || HARFBUZZ_DIR="${TOOLCHAINS_DIR}/src/harfbuzz"
[ -n "${JETBRAINS_MONO_DIR}" ] || JETBRAINS_MONO_DIR="${TOOLCHAINS_DIR}/src/jetbrains_mono"
[ -n "${NERD_FONTS_DIR}" ] || NERD_FONTS_DIR="${TOOLCHAINS_DIR}/src/nerd_fonts_symbols"

GVX_DIR="$(require_dir "gvx" "${GVX_DIR}")"
GHOSTTY_DIR="$(require_dir "ghostty" "${GHOSTTY_DIR}")"
FREETYPE_DIR="$(require_dir "freetype" "${FREETYPE_DIR}")"
HARFBUZZ_DIR="$(require_dir "harfbuzz" "${HARFBUZZ_DIR}")"
JETBRAINS_MONO_DIR="$(require_dir "jetbrains_mono" "${JETBRAINS_MONO_DIR}")"
NERD_FONTS_DIR="$(require_dir "nerd_fonts_symbols" "${NERD_FONTS_DIR}")"

GO_EXE="$(resolve_go_bin)"
ZIG_EXE="$(resolve_zig_bin)"

PINNED_NDK_VERSION="$(json_query ".toolchains.android_ndk")"
MIN_SDK="$(json_query ".toolchains.android_min_sdk")"
NDK_DIR="$(locate_ndk_dir "${PINNED_NDK_VERSION}")"
NDK_LLVM_BIN="$(locate_ndk_llvm_bin "${NDK_DIR}")"
NDK_TOOLCHAIN_FILE="${NDK_DIR}/build/cmake/android.toolchain.cmake"
NDK_LLVM_AR="${NDK_LLVM_BIN}/llvm-ar"
NDK_LLVM_RANLIB="${NDK_LLVM_BIN}/llvm-ranlib"
NDK_CLANG_X86_64="${NDK_LLVM_BIN}/x86_64-linux-android${MIN_SDK}-clang"

[ -x "${NDK_LLVM_AR}" ] || die "Missing llvm-ar at ${NDK_LLVM_AR}"
[ -x "${NDK_LLVM_RANLIB}" ] || die "Missing llvm-ranlib at ${NDK_LLVM_RANLIB}"
[ -x "${NDK_CLANG_X86_64}" ] || die "Missing NDK Clang at ${NDK_CLANG_X86_64}"
command -v cmake >/dev/null 2>&1 || die "cmake is required to build FreeType and HarfBuzz"

ZIG_SYSTEM_DIR="${ZIG_SYSTEM_PACKAGE_DIR:-${TOOLCHAINS_DIR}/zig-cache/p}"
if [ ! -d "${ZIG_SYSTEM_DIR}" ] && [ -d "${TOOLCHAINS_DIR}/zig-cache" ]; then
  ZIG_SYSTEM_DIR="${TOOLCHAINS_DIR}/zig-cache"
fi
[ -d "${ZIG_SYSTEM_DIR}" ] || die "Missing Zig package cache directory: ${ZIG_SYSTEM_DIR}"

export GOPROXY=off
export GOSUMDB=off
export GOTOOLCHAIN=local
export ZERO_AR_DATE=1
export SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-1700000000}"
export ANDROID_NDK_HOME="${NDK_DIR}"

CPU_JOBS="$(detect_cpu_count)"
BUILD_SCRATCH="${REPO_ROOT}/core/terminal-vt/build/native-deps-work"
rm -rf "${BUILD_SCRATCH}"
mkdir -p "${BUILD_SCRATCH}"

# Stage bundled font assets once across all ABIs.
FONTS_OUT="${REPO_ROOT}/core/terminal-ui/src/main/assets/fonts"
mkdir -p "${FONTS_OUT}"
for weight in Regular Bold Italic BoldItalic; do
  src_ttf="$(find "${JETBRAINS_MONO_DIR}" -type f -name "JetBrainsMono-${weight}.ttf" | head -n 1)"
  [ -n "${src_ttf}" ] || die "Missing JetBrainsMono-${weight}.ttf in ${JETBRAINS_MONO_DIR}"
  cp -f "${src_ttf}" "${FONTS_OUT}/JetBrainsMono-${weight}.ttf"
done
nerd_ttf="$(find "${NERD_FONTS_DIR}" -type f -name "SymbolsNerdFontMono-Regular.ttf" | head -n 1)"
[ -n "${nerd_ttf}" ] || die "Missing SymbolsNerdFontMono-Regular.ttf in ${NERD_FONTS_DIR}"
cp -f "${nerd_ttf}" "${FONTS_OUT}/SymbolsNerdFontMono-Regular.ttf"
log_info "Staged bundled fonts into ${FONTS_OUT}"

build_for_abi() {
  local abi="$1"
  local guest_arch=""
  local zig_target=""

  case "${abi}" in
    arm64-v8a)
      guest_arch="arm64"
      zig_target="aarch64-linux-android.${MIN_SDK}"
      ;;
    x86_64)
      guest_arch="amd64"
      zig_target="x86_64-linux-android.${MIN_SDK}"
      ;;
    *)
      die "Unsupported ABI: ${abi} (expected arm64-v8a or x86_64)"
      ;;
  esac

  log_info "Building offline native dependencies for ${abi}"

  # 1. Build librungv.so from vendored gvx checkout.
  [ -f "${GVX_DIR}/vendor/modules.txt" ] || die "Missing ${GVX_DIR}/vendor/modules.txt; run provision-toolchains-and-vendor.sh first"
  local jni_out_dir="${REPO_ROOT}/core/runtime-gvx/src/main/jniLibs/${abi}"
  mkdir -p "${jni_out_dir}"
  local rungv_out="${jni_out_dir}/librungv.so"

  (
    cd "${GVX_DIR}"
    if [ "${abi}" = "arm64-v8a" ]; then
      CGO_ENABLED=0 GOOS=linux GOARCH=arm64 "${GO_EXE}" build \
        -mod=vendor -trimpath -buildvcs=false \
        -ldflags="-s -w -buildid= -extldflags=-Wl,-z,max-page-size=16384" \
        -o "${rungv_out}" ./cmd/rungv
    else
      CGO_ENABLED=1 GOOS=android GOARCH=amd64 CC="${NDK_CLANG_X86_64}" "${GO_EXE}" build \
        -mod=vendor -trimpath -buildvcs=false \
        -ldflags="-s -w -buildid= -extldflags '-Wl,-z,max-page-size=16384 -Wl,--build-id=none'" \
        -o "${rungv_out}" ./cmd/rungv
    fi
  )
  log_info "Built ${rungv_out}"

  # 2. Build static pure-Go termvr-agent and enforce <= 2.2 MiB budget.
  local agent_out="${REPO_ROOT}/app/src/main/assets/agent/${guest_arch}/termvr-agent"
  mkdir -p "$(dirname "${agent_out}")"
  (
    cd "${REPO_ROOT}/guest/termvr-agent"
    CGO_ENABLED=0 GOOS=linux GOARCH="${guest_arch}" "${GO_EXE}" build \
      -trimpath -buildvcs=false \
      -ldflags="-s -w -buildid=" \
      -o "${agent_out}" ./cmd/termvr-agent
  )
  local agent_bytes
  agent_bytes="$(file_size_bytes "${agent_out}")"
  local max_agent_bytes=2306867
  if [ "${agent_bytes}" -gt "${max_agent_bytes}" ]; then
    die "termvr-agent (${guest_arch}) exceeds 2.2 MiB budget: ${agent_bytes} bytes > ${max_agent_bytes}"
  fi
  log_info "Built ${agent_out} (${agent_bytes} bytes)"

  # 3. Stage directories for C/C++ and Zig static dependencies.
  local deps_stage="${REPO_ROOT}/core/terminal-vt/build/native-deps/${abi}"
  local stage_lib="${deps_stage}/lib"
  local stage_inc="${deps_stage}/include"
  rm -rf "${deps_stage}"
  mkdir -p "${stage_lib}" "${stage_inc}"

  # 4. Build libghostty-vt.a completely offline via Zig.
  local ghostty_prefix="${BUILD_SCRATCH}/${abi}/ghostty-install"
  local ghostty_cache="${BUILD_SCRATCH}/${abi}/zig-global-cache"
  local ghostty_local_cache="${BUILD_SCRATCH}/${abi}/zig-local-cache"
  mkdir -p "${ghostty_prefix}" "${ghostty_cache}" "${ghostty_local_cache}"
  (
    cd "${GHOSTTY_DIR}"
    ZIG_GLOBAL_CACHE_DIR="${ghostty_cache}" "${ZIG_EXE}" build \
      -Doptimize=ReleaseFast \
      -Demit-lib-vt=true \
      -Demit-xcframework=false \
      -Dlib-version-string=1.0.1 \
      -Dstrip=true \
      --system "${ZIG_SYSTEM_DIR}" \
      --cache-dir "${ghostty_local_cache}" \
      --prefix "${ghostty_prefix}" \
      -Dtarget="${zig_target}"
  )
  [ -f "${ghostty_prefix}/lib/libghostty-vt.a" ] || die "Missing ${ghostty_prefix}/lib/libghostty-vt.a"
  cp -f "${ghostty_prefix}/lib/libghostty-vt.a" "${stage_lib}/libghostty-vt.a"
  if [ -d "${ghostty_prefix}/include" ]; then
    cp -R "${ghostty_prefix}/include/." "${stage_inc}/"
  elif [ -d "${GHOSTTY_DIR}/include" ]; then
    cp -R "${GHOSTTY_DIR}/include/." "${stage_inc}/"
  fi
  log_info "Staged libghostty-vt.a for ${abi}"

  # 5. Build libfreetype.a deterministically.
  local ft_build="${BUILD_SCRATCH}/${abi}/freetype-build"
  local ft_install="${BUILD_SCRATCH}/${abi}/freetype-install"
  local cmake_c_flags="-O2 -fPIC -ffile-prefix-map=${FREETYPE_DIR}=. -ffile-prefix-map=${REPO_ROOT}=."
  cmake -S "${FREETYPE_DIR}" -B "${ft_build}" \
    -DCMAKE_TOOLCHAIN_FILE="${NDK_TOOLCHAIN_FILE}" \
    -DANDROID_ABI="${abi}" \
    -DANDROID_PLATFORM="android-${MIN_SDK}" \
    -DANDROID_STL=c++_static \
    -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=OFF \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DCMAKE_AR="${NDK_LLVM_AR}" \
    -DCMAKE_RANLIB="${NDK_LLVM_RANLIB}" \
    -DCMAKE_C_FLAGS="${cmake_c_flags}" \
    -DCMAKE_INSTALL_PREFIX="${ft_install}" \
    -DFT_DISABLE_ZLIB=TRUE \
    -DFT_DISABLE_BZIP2=TRUE \
    -DFT_DISABLE_PNG=TRUE \
    -DFT_DISABLE_HARFBUZZ=TRUE \
    -DFT_DISABLE_BROTLI=TRUE
  cmake --build "${ft_build}" --parallel "${CPU_JOBS}"
  cmake --install "${ft_build}"
  cp -f "${ft_install}/lib/libfreetype.a" "${stage_lib}/libfreetype.a"
  "${NDK_LLVM_RANLIB}" -D "${stage_lib}/libfreetype.a"
  cp -R "${ft_install}/include/." "${stage_inc}/"
  log_info "Staged libfreetype.a for ${abi}"

  # 6. Build libharfbuzz.a deterministically.
  local hb_build="${BUILD_SCRATCH}/${abi}/harfbuzz-build"
  local hb_install="${BUILD_SCRATCH}/${abi}/harfbuzz-install"
  local hb_cxx_flags="-O2 -fPIC -ffile-prefix-map=${HARFBUZZ_DIR}=. -ffile-prefix-map=${REPO_ROOT}=."
  cmake -S "${HARFBUZZ_DIR}" -B "${hb_build}" \
    -DCMAKE_TOOLCHAIN_FILE="${NDK_TOOLCHAIN_FILE}" \
    -DANDROID_ABI="${abi}" \
    -DANDROID_PLATFORM="android-${MIN_SDK}" \
    -DANDROID_STL=c++_static \
    -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=OFF \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DCMAKE_AR="${NDK_LLVM_AR}" \
    -DCMAKE_RANLIB="${NDK_LLVM_RANLIB}" \
    -DCMAKE_C_FLAGS="-O2 -fPIC -ffile-prefix-map=${HARFBUZZ_DIR}=. -ffile-prefix-map=${REPO_ROOT}=." \
    -DCMAKE_CXX_FLAGS="${hb_cxx_flags}" \
    -DCMAKE_INSTALL_PREFIX="${hb_install}" \
    -DCMAKE_PREFIX_PATH="${ft_install}" \
    -DHB_HAVE_FREETYPE=ON \
    -DFREETYPE_INCLUDE_DIRS="${ft_install}/include/freetype2" \
    -DFREETYPE_LIBRARY="${ft_install}/lib/libfreetype.a" \
    -DHB_HAVE_GLIB=OFF \
    -DHB_HAVE_GOBJECT=OFF \
    -DHB_HAVE_ICU=OFF \
    -DHB_HAVE_GRAPHITE2=OFF \
    -DHB_BUILD_TESTS=OFF \
    -DHB_BUILD_UTILS=OFF
  cmake --build "${hb_build}" --parallel "${CPU_JOBS}"
  cmake --install "${hb_build}"
  cp -f "${hb_install}/lib/libharfbuzz.a" "${stage_lib}/libharfbuzz.a"
  "${NDK_LLVM_RANLIB}" -D "${stage_lib}/libharfbuzz.a"
  cp -R "${hb_install}/include/." "${stage_inc}/"
  log_info "Staged libharfbuzz.a for ${abi}"
}

IFS=',' read -r -a ABI_LIST <<<"${ABIS_CSV}"
for raw_abi in "${ABI_LIST[@]}"; do
  trimmed_abi="$(printf '%s' "${raw_abi}" | tr -d '[:space:]')"
  [ -n "${trimmed_abi}" ] || continue
  build_for_abi "${trimmed_abi}"
done

rm -rf "${BUILD_SCRATCH}"
log_info "Offline native build completed for: ${ABIS_CSV}"
