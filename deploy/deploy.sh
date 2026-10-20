#!/usr/bin/env bash
# 首次部署 / 重新拉起整套服务。
# 用法：bash deploy.sh
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}"

env_get() {
    sed -n "s/^$1=//p" .env 2>/dev/null | head -n 1 | tr -d '\r'
}

require_strong() {
    local name="$1"
    local value
    value="$(env_get "${name}")"
    case "${value}" in
        ""|replace-with-*)
            echo "请先在 deploy/.env 中设置 ${name}" >&2
            exit 1
            ;;
    esac
}

if ! command -v docker >/dev/null 2>&1; then
    echo "未安装 Docker，请先执行：sudo bash install-docker.sh" >&2
    exit 1
fi
if ! docker compose version >/dev/null 2>&1; then
    echo "缺少 Docker Compose 插件，请重新执行：sudo bash install-docker.sh" >&2
    exit 1
fi
if ! docker info >/dev/null 2>&1; then
    echo "无法访问 Docker 守护进程（未启动或无权限）。" >&2
    echo "请检查 systemctl status docker，或使用：sudo bash deploy.sh" >&2
    exit 1
fi

if [ ! -f .env ]; then
    cp .env.example .env
    chmod 600 .env
    echo "已生成 deploy/.env，请填写数据库密码、后台密码与模型 Key 后重新执行。" >&2
    exit 1
fi
chmod 600 .env

require_strong DB_PASSWORD
require_strong MYSQL_ROOT_PASSWORD
require_strong ADMIN_PASSWORD

HTTP_PORT="$(env_get HTTP_PORT)"
HTTP_PORT="${HTTP_PORT:-8080}"
if ! [[ "${HTTP_PORT}" =~ ^[0-9]+$ ]] || [ "${HTTP_PORT}" -lt 1 ] || [ "${HTTP_PORT}" -gt 65535 ]; then
    echo "HTTP_PORT 不合法：${HTTP_PORT}" >&2
    exit 1
fi

if [ ! -s app.jar ]; then
    if [ -s ../target/jqgg-glm53-1.0.0.jar ]; then
        cp ../target/jqgg-glm53-1.0.0.jar app.jar
    else
        echo "缺少 deploy/app.jar，请先在开发机执行 build-local.sh 并上传部署包。" >&2
        exit 1
    fi
fi

RAG_MODEL_DIR="$(env_get RAG_MODEL_DIR)"
RAG_MODEL_DIR="${RAG_MODEL_DIR:-model-small}"
MODEL_DIR="../rag-service/${RAG_MODEL_DIR}"
if [ ! -s "${MODEL_DIR}/onnx/model.onnx" ]; then
    echo "向量模型不完整：缺失 ${MODEL_DIR}/onnx/model.onnx" >&2
    echo "轻量模型应随部署包提供；如需 bge-m3，请执行：bash download-model.sh" >&2
    exit 1
fi
if [ "${RAG_MODEL_DIR}" = "model" ] \
    && [ "$(stat -c%s "${MODEL_DIR}/onnx/model.onnx_data" 2>/dev/null || echo 0)" -lt 2000000000 ]; then
    echo "bge-m3 模型数据文件不完整，请重新执行：bash download-model.sh" >&2
    exit 1
fi

echo "[deploy] 校验 Compose 配置"
docker compose config -q

echo "[deploy] 准备基础镜像"
bash fetch-images.sh

echo "[deploy] 构建应用与向量服务镜像"
docker compose build

echo "[deploy] 启动服务（首次启动需加载 bge-m3 模型）"
docker compose up -d --remove-orphans

echo "[deploy] 等待应用就绪（最多 10 分钟）"
READY=0
for _ in $(seq 1 120); do
    if curl -fsS --max-time 3 "http://127.0.0.1:${HTTP_PORT}/api/site-config" >/dev/null 2>&1; then
        READY=1
        break
    fi
    sleep 5
done

if [ "${READY}" -ne 1 ]; then
    echo "[deploy] 应用未在预期时间内就绪，以下是容器状态与日志：" >&2
    docker compose ps >&2 || true
    docker compose logs --tail=150 app >&2 || true
    docker compose logs --tail=80 rag >&2 || true
    exit 1
fi

echo "[deploy] 服务已启动："
docker compose ps

echo
echo "============================================================"
echo " 前台：http://<ECS公网IP>:${HTTP_PORT}/"
echo " 后台：http://<ECS公网IP>:${HTTP_PORT}/admin"
echo " 管理密码：见 deploy/.env 中的 ADMIN_PASSWORD"
echo " 请确认阿里云 ECS 安全组已放行 TCP ${HTTP_PORT}"
echo "============================================================"
