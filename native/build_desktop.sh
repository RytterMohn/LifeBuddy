#!/usr/bin/env bash
# 桌面调试构建：llama.cpp 静态库 + 本 JNI → native/build/libllama.dylib（macOS）
#
# 用法: ./native/build_desktop.sh [llama.cpp 路径]   (默认 third_party/llama.cpp 或 /tmp/llama.cpp)
# 前置: cmake（brew install cmake 或官网二进制包）
set -euo pipefail

cd "$(dirname "$0")/.."   # 项目根

LLAMA_DIR="${1:-}"
if [[ -z "$LLAMA_DIR" ]]; then
  for cand in third_party/llama.cpp /tmp/llama.cpp; do
    [[ -f "$cand/CMakeLists.txt" ]] && LLAMA_DIR="$cand" && break
  done
fi
[[ -z "$LLAMA_DIR" || ! -f "$LLAMA_DIR/CMakeLists.txt" ]] && {
  echo "找不到 llama.cpp，请先: git clone --depth 1 https://github.com/ggml-org/llama.cpp third_party/llama.cpp"; exit 1
}

CMAKE="${CMAKE:-$(command -v cmake || echo '')}"
[[ -z "$CMAKE" ]] && { echo "需要 cmake（brew install cmake）"; exit 1; }
JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home 2>/dev/null || echo '')}"
[[ -z "$JAVA_HOME" ]] && { echo "需要 JDK（设 JAVA_HOME）"; exit 1; }

echo "==> llama.cpp: $LLAMA_DIR  cmake: $CMAKE"
"$CMAKE" -S "$LLAMA_DIR" -B "$LLAMA_DIR/build-desktop" \
  -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_SHARED_LIBS=OFF \
  -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_SERVER=OFF \
  -DLLAMA_CURL=OFF -DGGML_METAL=OFF -DGGML_ACCELERATE=ON >/dev/null
"$CMAKE" --build "$LLAMA_DIR/build-desktop" --target llama --config Release -j8 >/dev/null

mkdir -p native/build
LIBS=""
for lib in libllama.a libggml.a libggml-base.a libggml-cpu.a libggml-blas.a; do
  f=$(find "$LLAMA_DIR/build-desktop" -name "$lib" 2>/dev/null | head -1)
  [[ -n "$f" ]] && LIBS="$LIBS $f"
done
[[ -z "$LIBS" ]] && { echo "静态库未生成"; exit 1; }

clang++ -std=c++17 -O3 \
  -I"$LLAMA_DIR/include" -I"$LLAMA_DIR/ggml/include" \
  -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/darwin" \
  -dynamiclib -o native/build/libllama.dylib \
  native/llama_jni.cpp $LIBS -framework Accelerate -lpthread -ldl -lm

echo "==> 产物: $(ls -lh native/build/libllama.dylib | awk '{print $9, $5}')"
echo "    测试: ./gradlew :cli:run --args=\"--llama models/gemma-3-4b-it-Q4_K_M.gguf\""
