#!/usr/bin/env bash
# 打正式包：编译 release → 校验版本 → 只把最新产物放进交付目录（自动清掉旧包）。
# 用法： ./scripts/release.sh
# 交付目录默认是脚本上一级（/workspace），可用 OUT_DIR 覆盖。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="${OUT_DIR:-$(cd "$ROOT/.." && pwd)}"
ANDROID_HOME="${ANDROID_HOME:-/root/Android}"
GRADLE_BIN="${GRADLE_BIN:-/opt/gradle-8.9/bin/gradle}"

cd "$ROOT"

VER=$(grep -oP 'versionName\s*=\s*"\K[^"]+' app/build.gradle.kts | head -1)
CODE=$(grep -oP 'versionCode\s*=\s*\K[0-9]+' app/build.gradle.kts | head -1)
echo "==> 构建版本 versionName=$VER versionCode=$CODE"

echo "==> 编译 release ..."
ANDROID_HOME="$ANDROID_HOME" "$GRADLE_BIN" --offline --no-daemon assembleRelease

APK_SRC="$ROOT/app/build/outputs/apk/release/app-release.apk"
AAPT2=$(find "$ANDROID_HOME/build-tools" -type f -name aapt2 | sort -V | tail -1)
echo "==> 校验版本："
"$AAPT2" dump badging "$APK_SRC" 2>/dev/null | grep -E "^package:" | head -1

# 关键：先清空旧交付物，再放新的——保证目录里永远只有当前这一版。
echo "==> 清理旧产物（$OUT_DIR）..."
find "$OUT_DIR" -maxdepth 1 -type f \( -name 'AIpod-v*.apk' -o -name 'AIpod-*-source.zip' \) -print -delete

APK_OUT="$OUT_DIR/AIpod-v${VER}.apk"
ZIP_OUT="$OUT_DIR/AIpod-${VER}-source.zip"
cp "$APK_SRC" "$APK_OUT"
# 源码包必须包含本次改动。旧版用 `git archive HEAD`，但本脚本在 git commit 之前运行，
# 那样打出来的是上一版内容。改为从当前工作树快照打包（内容一定是磁盘上的最新源码）。
echo "==> 从工作树打包源码（含本次改动）..."
TMP_TREE=$(mktemp -d)
trap 'rm -rf "$TMP_TREE"' EXIT
git ls-files -co --exclude-standard | while IFS= read -r f; do
  mkdir -p "$TMP_TREE/AIpod/$(dirname "$f")"
  cp "$f" "$TMP_TREE/AIpod/$f"
done
( cd "$TMP_TREE" && zip -q -r "$ZIP_OUT" AIpod )

echo "==> 交付物："
ls -lh "$APK_OUT" "$ZIP_OUT"
