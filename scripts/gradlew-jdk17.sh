#!/usr/bin/env bash

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCAL_ANDROID_ENV="$PROJECT_ROOT/android-env.local.properties"
ANDROID_JAVA_HOME="${JAVA_HOME:-}"

if [[ -z "$ANDROID_JAVA_HOME" && -f "$LOCAL_ANDROID_ENV" ]]; then
    ANDROID_JAVA_HOME="$(sed -n 's/^java.home=//p' "$LOCAL_ANDROID_ENV" | tail -n 1)"
fi

if [[ -z "$ANDROID_JAVA_HOME" || ! -x "$ANDROID_JAVA_HOME/bin/java" ]]; then
    echo "未找到 JDK 17。请设置 JAVA_HOME 或 android-env.local.properties 的 java.home。" >&2
    exit 1
fi

exec env JAVA_HOME="$ANDROID_JAVA_HOME" "$PROJECT_ROOT/gradlew" --project-dir "$PROJECT_ROOT" "$@"
