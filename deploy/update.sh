#!/usr/bin/env bash
# 更新应用：先备份，再重建 app 镜像并重启应用容器。
# 数据库、上传图片和向量模型不受影响。
# 用法：bash update.sh          仅更新应用
#       bash update.sh --all    同时更新向量服务镜像
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}"

if [ ! -s app.jar ]; then
    echo "缺少 deploy/app.jar，请先上传新的应用包。" >&2
    exit 1
fi

bash backup.sh

if [ "${1:-}" = "--all" ]; then
    docker compose build
    docker compose up -d --no-deps --force-recreate app rag
else
    docker compose build app
    docker compose up -d --no-deps --force-recreate app
fi

PORT="$(sed -n 's/^HTTP_PORT=//p' .env | head -n 1 | tr -d '\r')"
PORT="${PORT:-8080}"
for _ in $(seq 1 60); do
    if curl -fsS --max-time 3 "http://127.0.0.1:${PORT}/api/site-config" >/dev/null 2>&1; then
        echo "[update] 更新完成"
        docker compose ps
        exit 0
    fi
    sleep 3
done

echo "[update] 应用未就绪，请查看日志：" >&2
docker compose logs --tail=150 app >&2
exit 1
