#!/usr/bin/env bash
set -euo pipefail

if ! command -v adb >/dev/null 2>&1 && [ -n "${ANDROID_HOME:-}" ] && [ -x "${ANDROID_HOME}/platform-tools/adb" ]; then
  export PATH="${ANDROID_HOME}/platform-tools:${PATH}"
fi

page_size="$(adb shell getconf PAGESIZE | tr -d '\r')"
echo "Emulator page size: ${page_size}"
if [ "${page_size}" != "16384" ]; then
  echo "Expected 16384-byte page size on Android 16 16 KB emulator, got ${page_size}" >&2
  exit 1
fi

apk_path="$(find /tmp/termvr-apks -type f -name '*debug*.apk' | head -n 1)"
test -n "${apk_path}"
adb install -r "${apk_path}"
adb logcat -c
adb shell am start -W -n dev.gfire.termvr/.MainActivity

app_pid=""
for _ in $(seq 1 20); do
  app_pid="$(adb shell pidof dev.gfire.termvr 2>/dev/null | tr -d '\r' || true)"
  if [ -n "${app_pid}" ]; then
    break
  fi
  sleep 1
done

if [ -z "${app_pid}" ]; then
  echo "dev.gfire.termvr process is not running after launch" >&2
  adb logcat -d -s AndroidRuntime:E libc:F DEBUG:* ActivityManager:I ActivityTaskManager:I || true
  exit 1
fi

echo "dev.gfire.termvr running with pid ${app_pid}"
sleep 3

if ! adb shell pidof dev.gfire.termvr >/dev/null 2>&1; then
  echo "dev.gfire.termvr exited shortly after launch" >&2
  adb logcat -d -s AndroidRuntime:E libc:F DEBUG:* || true
  exit 1
fi

if adb logcat -d -s AndroidRuntime:E libc:F | grep -q "FATAL EXCEPTION"; then
  adb logcat -d -s AndroidRuntime:E libc:F
  exit 1
fi
