#!/usr/bin/env bash

set -euo pipefail

INSTALL_MODE="install-only"
case "${1:-}" in
    ""|--install-only)
        ;;
    --runtime-smoke)
        INSTALL_MODE="runtime-smoke"
        ;;
    -h|--help)
        echo "用法：$0 [--install-only|--runtime-smoke]"
        echo "默认只覆盖安装 Debug APK，不启动应用。"
        echo "--runtime-smoke：覆盖安装、启动并检查进程、服务和监听端口。"
        exit 0
        ;;
    *)
        echo "未知参数：$1" >&2
        echo "用法：$0 [--install-only|--runtime-smoke]" >&2
        exit 2
        ;;
esac

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCAL_ANDROID_PROPERTIES="$PROJECT_ROOT/local.properties"
DEBUG_APK="$PROJECT_ROOT/app/build/outputs/apk/debug/app-debug.apk"
EXPECTED_PACKAGE="com.ninepointnine.desktopcast"
EXPECTED_COMPONENT="$EXPECTED_PACKAGE/.MainActivity"
EXPECTED_SERVICE="$EXPECTED_PACKAGE/.service.CastService"

read_property() {
    local file_path="$1"
    local property_name="$2"
    sed -n "s/^${property_name}=//p" "$file_path" | tail -n 1
}

ANDROID_SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$ANDROID_SDK_DIR" && -f "$LOCAL_ANDROID_PROPERTIES" ]]; then
    ANDROID_SDK_DIR="$(read_property "$LOCAL_ANDROID_PROPERTIES" "sdk.dir")"
fi

ANDROID_ADB_BIN="${ANDROID_ADB:-$ANDROID_SDK_DIR/platform-tools/adb}"
ANDROID_AAPT_BIN="$(find "$ANDROID_SDK_DIR/build-tools" -type f -name aapt 2>/dev/null | sort -V | tail -n 1)"

if [[ ! -x "$ANDROID_ADB_BIN" || ! -x "$ANDROID_AAPT_BIN" ]]; then
    echo "未找到可执行 Android Platform Tools 或 Build Tools。" >&2
    exit 1
fi

if [[ ! -f "$DEBUG_APK" ]]; then
    echo "未找到 Debug APK；请先完成 assembleDebug。" >&2
    exit 1
fi

APK_PACKAGE="$($ANDROID_AAPT_BIN dump badging "$DEBUG_APK" | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -n 1)"
if [[ "$APK_PACKAGE" != "$EXPECTED_PACKAGE" ]]; then
    echo "Debug APK 包名不匹配：$APK_PACKAGE" >&2
    exit 1
fi

TARGET_SERIAL="${ANDROID_DEVICE_SERIAL:-}"
if [[ -z "$TARGET_SERIAL" ]]; then
    while IFS= read -r serial; do
        [[ -z "$serial" ]] && continue
        device_model="$($ANDROID_ADB_BIN -s "$serial" shell getprop ro.product.model | tr -d '\r')"
        if [[ "$device_model" == "S56_HQX" ]]; then
            if [[ -n "$TARGET_SERIAL" ]]; then
                echo "检测到多个 S56_HQX 车机，请设置 ANDROID_DEVICE_SERIAL。" >&2
                exit 1
            fi
            TARGET_SERIAL="$serial"
        fi
    done < <("$ANDROID_ADB_BIN" devices | awk 'NR > 1 && $2 == "device" { print $1 }')
fi

if [[ -z "$TARGET_SERIAL" ]]; then
    echo "车机未处于可用连接状态。" >&2
    exit 1
fi

adb_target() {
    "$ANDROID_ADB_BIN" -s "$TARGET_SERIAL" "$@"
}

if [[ "$(adb_target get-state 2>/dev/null || true)" != "device" ]]; then
    echo "车机未处于可用连接状态：$TARGET_SERIAL" >&2
    exit 1
fi

DEVICE_MODEL="$(adb_target shell getprop ro.product.model | tr -d '\r')"
DEVICE_SDK="$(adb_target shell getprop ro.build.version.sdk | tr -d '\r')"
DEVICE_SIZE="$(adb_target shell wm size | tr -d '\r')"

if [[ "$DEVICE_MODEL" != "S56_HQX" || "$DEVICE_SDK" != "28" || "$DEVICE_SIZE" != *"1920x1080"* ]]; then
    echo "设备基线不匹配：model=$DEVICE_MODEL sdk=$DEVICE_SDK size=$DEVICE_SIZE" >&2
    exit 1
fi

echo "目标车机：$DEVICE_MODEL / Android SDK $DEVICE_SDK / 1920x1080"
adb_target install -r -g "$DEBUG_APK"
if [[ "$INSTALL_MODE" == "install-only" ]]; then
    echo "Debug APK 已覆盖安装，未启动应用；等待用户手测。"
    exit 0
fi

adb_target shell am start -n "$EXPECTED_COMPONENT"

PROCESS_ID=""
SERVICE_STATE=""
LISTEN_STATE=""
for _ in {1..40}; do
    PROCESS_ID="$(adb_target shell pidof "$EXPECTED_PACKAGE" 2>/dev/null | tr -d '\r' || true)"
    SERVICE_STATE="$(adb_target shell dumpsys activity services "$EXPECTED_PACKAGE" 2>/dev/null | tr -d '\r' || true)"
    LISTEN_STATE="$(adb_target shell netstat -an 2>/dev/null | tr -d '\r' || true)"
    if [[ -n "$PROCESS_ID" && "$SERVICE_STATE" == *"$EXPECTED_SERVICE"* &&
        "$LISTEN_STATE" == *":7000"* && "$LISTEN_STATE" == *":8200"* ]]; then
        break
    fi
    sleep 0.5
done

if [[ -z "$PROCESS_ID" ]]; then
    echo "应用启动后未检测到进程：$EXPECTED_PACKAGE" >&2
    exit 1
fi

if [[ "$SERVICE_STATE" != *"$EXPECTED_SERVICE"* ]]; then
    echo "应用已启动，但投屏接收服务未运行。" >&2
    exit 1
fi

if [[ "$LISTEN_STATE" != *":7000"* || "$LISTEN_STATE" != *":8200"* ]]; then
    echo "接收服务已运行，但 AirPlay 或 DLNA 监听端口尚未就绪。" >&2
    exit 1
fi

LAST_UPDATE_TIME="$(adb_target shell dumpsys package "$EXPECTED_PACKAGE" | tr -d '\r' | sed -n 's/^[[:space:]]*lastUpdateTime=//p' | head -n 1)"
echo "应用进程：$PROCESS_ID"
echo "AirPlay 7000 / DLNA 8200：监听中"
echo "覆盖时间：$LAST_UPDATE_TIME"
echo "Debug APK 已覆盖安装并启动。"
