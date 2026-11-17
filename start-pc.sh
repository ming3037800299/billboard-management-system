#!/usr/bin/env bash
# 启动 PC 端（后端单进程同时提供 / 与 /admin；移动端 /m/ 未改动）
set -Eeuo pipefail
cd "$(dirname "$0")"

export DB_MAINT_USERNAME="${DB_MAINT_USERNAME:-jqgg_maint_user}"
export DB_MAINT_PASSWORD="${DB_MAINT_PASSWORD:-}"

echo "启动公路广告牌租赁定位系统（PC 端）..."
echo "  前台: http://localhost:8080/"
echo "  后台: http://localhost:8080/admin"
# 明确告诉应用“项目根目录”，避免从其他工作目录启动时把上传文件写错位置
exec java -Dapp.home="$(pwd)" -jar deploy/app.jar
