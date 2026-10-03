#!/usr/bin/env bash
# 下载 Gemma 3 GGUF 量化模型（HuggingFace 官方量化仓库 ggml-org）
#
# 用法：
#   ./scripts/download_model.sh                # 默认 gemma-3-4b-it Q4_K_M (~2.6GB)
#   ./scripts/download_model.sh gemma-3-1b-it   # 轻量版 (~0.8GB)
#   ./scripts/download_model.sh gemma-3-4b-it Q8_0
set -euo pipefail

MODEL="${1:-gemma-3-4b-it}"
QUANT="${2:-Q4_K_M}"
OUT_DIR="models"
FILE="${MODEL}-${QUANT}.gguf"
URL="https://huggingface.co/ggml-org/${MODEL}-GGUF/resolve/main/${FILE}"

mkdir -p "$OUT_DIR"

if [[ -f "$OUT_DIR/$FILE" ]]; then
  echo "已存在: $OUT_DIR/$FILE，跳过"
  exit 0
fi

echo "下载: $URL"
echo "→ $OUT_DIR/$FILE"

if command -v huggingface-cli >/dev/null 2>&1; then
  huggingface-cli download "ggml-org/${MODEL}-GGUF" "$FILE" --local-dir "$OUT_DIR"
else
  # 兜底：curl 断点续传
  curl -L --fail --retry 3 -C - -o "$OUT_DIR/$FILE" "$URL"
fi

echo ""
echo "✅ 完成。手机端把文件放入 app 外部存储目录: Android/data/dev.ondevice.gemma.app/files/models/"
echo "   或桌面 CLI: ./gradlew :cli:run --args=\"--llama $OUT_DIR/$FILE\""
