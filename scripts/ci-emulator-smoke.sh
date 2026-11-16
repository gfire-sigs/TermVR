#!/usr/bin/env bash
set -euo pipefail

if ! command -v adb >/dev/null 2>&1 && [ -n "${ANDROID_HOME:-}" ] && [ -x "${ANDROID_HOME}/platform-tools/adb" ]; then
  export PATH="${ANDROID_HOME}/platform-tools:${PATH}"
fi

dump_failure_logs() {
  echo "=== crash logcat ===" >&2
  adb logcat -d -b crash -v threadtime || true
  echo "=== filtered main/system logcat ===" >&2
  adb logcat -d -b main,system -v threadtime | grep -iE 'termvr|AndroidRuntime|libc|DEBUG|fatal|crash|sig|kill|died|lmkd|lowmemory|oom|anr|OpenGLRenderer|RenderThread|hwui' | tail -n 150 || true
  echo "=== activity processes ===" >&2
  adb shell dumpsys activity processes dev.gfire.termvr | tail -n 60 || true
}

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
  dump_failure_logs
  exit 1
fi

echo "dev.gfire.termvr running with pid ${app_pid}"
sleep 3

if ! adb shell pidof dev.gfire.termvr >/dev/null 2>&1; then
  echo "dev.gfire.termvr exited shortly after launch" >&2
  dump_failure_logs
  exit 1
fi

if adb logcat -d -s AndroidRuntime:E libc:F | grep -q "FATAL EXCEPTION"; then
  adb logcat -d -s AndroidRuntime:E libc:F
  exit 1
fi
