#!/usr/bin/env bash
# 快速检查容器、网站与向量服务状态。
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}"

PORT="$(sed -n 's/^HTTP_PORT=//p' .env 2>/dev/null | head -n 1 | tr -d '\r')"
PORT="${PORT:-8080}"

echo "===== 容器状态 ====="
docker compose ps

echo
echo "===== 网站健康检查 ====="
if curl -fsS --max-time 5 "http://127.0.0.1:${PORT}/api/site-config"; then
    echo
else
    echo "网站无响应" >&2
    exit 1
fi

echo
echo "===== 向量服务健康检查 ====="
docker compose exec -T rag python -c \
    "import urllib.request; print(urllib.request.urlopen('http://127.0.0.1:11434/health', timeout=5).read().decode())"

echo
echo "===== 最近应用日志 ====="
docker compose logs --tail=30 app
