#!/usr/bin/env bash
# 下载轻量中文向量模型（bge-small-zh-v1.5 ONNX 量化版，约 24MB）。
# 适用于 2GB 内存服务器；部署包通常已附带此模型。
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET="${1:-${SCRIPT_DIR}/../rag-service/model-small}"
BASE="${MODEL_BASE_URL:-https://www.modelscope.cn/models/Xenova/bge-small-zh-v1.5/resolve/master}"

mkdir -p "${TARGET}/onnx"

download() {
    local remote="$1"
    local dest="$2"
    local min_size="${3:-1}"

    if [ -f "${dest}" ] && [ "$(stat -c%s "${dest}" 2>/dev/null || echo 0)" -ge "${min_size}" ]; then
        echo "[model-small] 已存在，跳过：${dest}"
        return 0
    fi
    echo "[model-small] 下载：${remote}"
    curl -L --fail --retry 5 --retry-delay 3 --continue-at - \
        --connect-timeout 20 --progress-bar -o "${dest}" "${BASE}/${remote}" || true
    local size
    size="$(stat -c%s "${dest}" 2>/dev/null || echo 0)"
    if [ "${size}" -lt "${min_size}" ]; then
        echo "[model-small] 下载不完整：${dest}（${size} 字节）" >&2
        exit 1
    fi
}

download "config.json" "${TARGET}/config.json"
download "tokenizer.json" "${TARGET}/tokenizer.json" 100000
download "tokenizer_config.json" "${TARGET}/tokenizer_config.json"
download "special_tokens_map.json" "${TARGET}/special_tokens_map.json"
download "onnx/model_quantized.onnx" "${TARGET}/onnx/model.onnx" 15000000

chmod -R a+rX "${TARGET}"
echo "[model-small] 完成：${TARGET}"
du -sh "${TARGET}"
