#!/usr/bin/env bash
# 在开发机（Git Bash / Linux / macOS）构建前端、后端并生成可上传的部署包。
# 用法：
#   bash deploy/build-local.sh                 生成不含模型的轻量包
#   bash deploy/build-local.sh --with-model    连同 2.3GB 模型一起打包
#   bash deploy/build-local.sh --with-env      把 deploy/.env 一起打包
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
INCLUDE_MODEL=0
INCLUDE_ENV=0

for arg in "$@"; do
    case "${arg}" in
        --with-model) INCLUDE_MODEL=1 ;;
        --with-env) INCLUDE_ENV=1 ;;
        *) echo "未知参数：${arg}" >&2; exit 1 ;;
    esac
done

echo "[build] 构建电脑端 Vue 前端"
cd "${ROOT}/frontend-pc"
if [ ! -d node_modules ]; then
    npm ci --no-audit --no-fund
fi
npm run build

echo "[build] 构建手机端 Vue 前端"
cd "${ROOT}/frontend-mobile"
if [ ! -d node_modules ]; then
    npm ci --no-audit --no-fund
fi
npm run build

echo "[build] 组装两套静态资源"
rm -rf "${ROOT}/src/main/resources/static"
mkdir -p "${ROOT}/src/main/resources/static/m"
cp -a "${ROOT}/.build/pc/." "${ROOT}/src/main/resources/static/"
cp -a "${ROOT}/.build/mobile/." "${ROOT}/src/main/resources/static/m/"

echo "[build] 构建 Spring Boot 后端"
# 在独立临时目录构建：避免 Windows 上本地正在运行的 jar 锁文件，
# 同时保证 target/classes/static 不残留旧哈希资源。
BUILD_DIR="${SCRIPT_DIR}/.build-tmp"
rm -rf "${BUILD_DIR}"
mkdir -p "${BUILD_DIR}"
tar -C "${ROOT}" -cf - pom.xml src | tar -C "${BUILD_DIR}" -xf -
(
    cd "${BUILD_DIR}"
    mvn -q clean package -DskipTests
)
cp "${BUILD_DIR}/target/jqgg-glm53-1.0.0.jar" "${SCRIPT_DIR}/app.jar"
rm -rf "${BUILD_DIR}"

echo "[build] 组装部署目录"
BUNDLE_ROOT="${SCRIPT_DIR}/bundle"
STAGE="${BUNDLE_ROOT}/jqgg-deploy"
rm -rf "${BUNDLE_ROOT}"
mkdir -p "${STAGE}/deploy/mysql" "${STAGE}/sql" "${STAGE}/rag-service"

cp "${SCRIPT_DIR}/Dockerfile" "${SCRIPT_DIR}/.dockerignore" "${SCRIPT_DIR}/docker-compose.yml" \
   "${SCRIPT_DIR}/.env.example" "${SCRIPT_DIR}/app.jar" \
   "${SCRIPT_DIR}/install-docker.sh" "${SCRIPT_DIR}/fetch-images.sh" \
   "${SCRIPT_DIR}/download-model.sh" "${SCRIPT_DIR}/download-model-small.sh" "${SCRIPT_DIR}/deploy.sh" \
   "${SCRIPT_DIR}/update.sh" "${SCRIPT_DIR}/backup.sh" \
   "${SCRIPT_DIR}/healthcheck.sh" "${SCRIPT_DIR}/README.md" \
   "${STAGE}/deploy/"

cp "${SCRIPT_DIR}/mysql/02-secure-local-user.sh" "${STAGE}/deploy/mysql/"

cp "${ROOT}/sql/init.sql" "${ROOT}/sql/db-maintenance.sql" "${ROOT}/sql/contract-module.sql" \
   "${ROOT}/sql/billboard-image-meta.sql" "${ROOT}/sql/concurrency-fixes.sql" "${STAGE}/sql/"
cp "${ROOT}/rag-service/Dockerfile" "${ROOT}/rag-service/.dockerignore" \
   "${ROOT}/rag-service/embed_server.py" "${ROOT}/rag-service/requirements.txt" \
   "${STAGE}/rag-service/"

if [ "${INCLUDE_ENV}" -eq 1 ] && [ -f "${SCRIPT_DIR}/.env" ]; then
    cp "${SCRIPT_DIR}/.env" "${STAGE}/deploy/.env"
fi

if [ ! -s "${ROOT}/rag-service/model-small/onnx/model.onnx" ]; then
    echo "缺少轻量向量模型 rag-service/model-small，请先运行 download-model-small.sh" >&2
    exit 1
fi
cp -a "${ROOT}/rag-service/model-small" "${STAGE}/rag-service/model-small"

if [ "${INCLUDE_MODEL}" -eq 1 ]; then
    echo "[build] 复制 bge-m3 模型（约 2.3GB）"
    cp -a "${ROOT}/rag-service/model" "${STAGE}/rag-service/model"
fi

echo "[build] 生成压缩包"
tar -czf "${BUNDLE_ROOT}/jqgg-deploy.tar.gz" -C "${BUNDLE_ROOT}" jqgg-deploy

echo "[build] 完成：${BUNDLE_ROOT}/jqgg-deploy.tar.gz"
du -h "${BUNDLE_ROOT}/jqgg-deploy.tar.gz"
echo
echo "上传后执行："
echo "  tar -xzf jqgg-deploy.tar.gz && cd jqgg-deploy"
echo "  sudo bash deploy/install-docker.sh"
echo "  sudo bash deploy/deploy.sh"
echo "（部署包已附带轻量向量模型；如需 bge-m3 再执行 download-model.sh）"
