#!/usr/bin/env bash
# 下载 bge-m3 ONNX 模型（约 2.3GB），支持断点续传。
# 用法：bash download-model.sh [模型目录]
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET="${1:-${SCRIPT_DIR}/../rag-service/model}"
BASE="${MODEL_BASE_URL:-https://www.modelscope.cn/models/BAAI/bge-m3/resolve/master}"

mkdir -p "${TARGET}/onnx" "${TARGET}/1_Pooling"

AVAIL_KB="$(df -Pk "${TARGET}" | awk 'NR==2 {print $4}')"
if [ "${AVAIL_KB:-0}" -lt 3500000 ]; then
    echo "磁盘剩余空间不足 3.5GB，无法下载模型（当前约 $((AVAIL_KB / 1024))MB）" >&2
    exit 1
fi

download() {
    local rel="$1"
    local min_size="${2:-1}"
    local dest="${TARGET}/${rel}"
    local url="${BASE}/${rel}"

    if [ -f "${dest}" ] && [ "$(stat -c%s "${dest}" 2>/dev/null || echo 0)" -ge "${min_size}" ]; then
        echo "[model] 已存在，跳过：${rel}"
        return 0
    fi

    echo "[model] 下载：${rel}"
    curl -L --fail --retry 5 --retry-delay 3 --retry-all-errors \
        --continue-at - --connect-timeout 20 --progress-bar \
        -o "${dest}" "${url}" || true

    local size
    size="$(stat -c%s "${dest}" 2>/dev/null || echo 0)"
    if [ "${size}" -lt "${min_size}" ]; then
        echo "[model] 下载不完整：${rel}（当前 ${size} 字节）" >&2
        exit 1
    fi
}

download "config.json"
download "tokenizer.json" 1000000
download "tokenizer_config.json"
download "sentencepiece.bpe.model" 1000000
download "modules.json"
download "sentence_bert_config.json"
download "1_Pooling/config.json"
download "onnx/config.json"
download "onnx/model.onnx" 500000
download "onnx/model.onnx_data" 2000000000

chmod -R a+rX "${TARGET}"

echo "[model] bge-m3 模型准备完成：${TARGET}"
du -sh "${TARGET}"
