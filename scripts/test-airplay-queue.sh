#!/usr/bin/env bash
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
QUEUE_TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$QUEUE_TEST_DIR"' EXIT
"${CC:-cc}" -std=c17 -D_POSIX_C_SOURCE=200809L -g -fsanitize=address,undefined \
  -I "$PROJECT_ROOT/app/src/main/cpp/third_party/UxPlay/lib" \
  "$PROJECT_ROOT/app/src/main/cpp/third_party/UxPlay/lib/airplay_video.c" \
  "$PROJECT_ROOT/app/src/test/cpp/airplay_video_queue_test.c" \
  -pthread -lm -o "$QUEUE_TEST_DIR/airplay-queue-test"
"$QUEUE_TEST_DIR/airplay-queue-test"
