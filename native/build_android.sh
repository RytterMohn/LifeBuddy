#!/usr/bin/env bash
# Optional local-inference build. The cloud / offline-practice APK does not need an NDK.
set -euo pipefail
cd "$(dirname "$0")/.."
LLAMA_DIR="${1:-third_party/llama.cpp}"
[[ -f "$LLAMA_DIR/CMakeLists.txt" ]] || { echo '请先准备 llama.cpp，并 checkout native/llama-revision.txt 中的版本'; exit 1; }
EXPECTED_REVISION="$(tr -d '\r\n' < native/llama-revision.txt)"
ACTUAL_REVISION="$(git -C "$LLAMA_DIR" rev-parse HEAD)"
[[ "$ACTUAL_REVISION" == "$EXPECTED_REVISION" ]] || { echo "llama.cpp 版本不匹配，需要 $EXPECTED_REVISION"; exit 1; }
LLAMA_DIR="$(cd "$LLAMA_DIR" && pwd)"
NDK="${ANDROID_NDK:-}"
[[ -f "$NDK/build/cmake/android.toolchain.cmake" ]] || { echo '请设置 ANDROID_NDK 为实际 NDK 目录'; exit 1; }
ABI="${ANDROID_ABI:-arm64-v8a}"
case "$ABI" in arm64-v8a|x86_64) ;; *) echo '仅验证 arm64-v8a / x86_64 构建配置'; exit 1;; esac
cmake -S native -B "native/build/android-$ABI" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI="$ABI" -DANDROID_PLATFORM=26 -DCMAKE_BUILD_TYPE=Release \
  -DLLAMA_SOURCE_DIR="$LLAMA_DIR"
cmake --build "native/build/android-$ABI" --target mobile_agent_jni -j4
mkdir -p "app/src/main/jniLibs/$ABI"
cp "native/build/android-$ABI/libllama.so" "app/src/main/jniLibs/$ABI/libllama.so"
echo "JNI 已生成：app/src/main/jniLibs/$ABI/libllama.so；请重新构建 APK"
