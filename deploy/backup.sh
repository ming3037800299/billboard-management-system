#!/usr/bin/env bash
# 备份 MySQL 与上传图片，默认保留 14 天。
# 用法：bash backup.sh
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}"

if [ ! -f .env ]; then
    echo "缺少 deploy/.env" >&2
    exit 1
fi

STAMP="$(date +%Y%m%d-%H%M%S)"
BACKUP_DIR="${SCRIPT_DIR}/backups"
mkdir -p "${BACKUP_DIR}"
chmod 700 "${BACKUP_DIR}"

if [ ! -z "$(docker compose ps -q mysql 2>/dev/null)" ]; then
    echo "[backup] 导出数据库"
    docker compose exec -T mysql sh -c \
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --quick --routines --triggers --default-character-set=utf8mb4 --databases jqgg_glm53' \
        | gzip > "${BACKUP_DIR}/mysql-${STAMP}.sql.gz"
    echo "[backup] 数据库 -> ${BACKUP_DIR}/mysql-${STAMP}.sql.gz"
else
    echo "[backup] MySQL 容器未运行，跳过数据库备份" >&2
fi

if docker image inspect jqgg-app:1.0.0 >/dev/null 2>&1; then
    echo "[backup] 打包上传图片"
    docker run --rm \
        --user 0:0 \
        --entrypoint /bin/tar \
        -v jqgg_uploads:/data:ro \
        -v "${BACKUP_DIR}:/backup" \
        jqgg-app:1.0.0 \
        -czf "/backup/uploads-${STAMP}.tar.gz" -C /data .
    echo "[backup] 图片 -> ${BACKUP_DIR}/uploads-${STAMP}.tar.gz"

    echo "[backup] 打包合同 / 模板 / 签章"
    docker run --rm \
        --user 0:0 \
        --entrypoint /bin/tar \
        -v jqgg_contracts:/data:ro \
        -v "${BACKUP_DIR}:/backup" \
        jqgg-app:1.0.0 \
        -czf "/backup/contracts-${STAMP}.tar.gz" -C /data .
    echo "[backup] 合同 -> ${BACKUP_DIR}/contracts-${STAMP}.tar.gz"
else
    echo "[backup] 应用镜像不存在，跳过图片 / 合同备份" >&2
fi

chmod 600 "${BACKUP_DIR}"/*.gz 2>/dev/null || true
find "${BACKUP_DIR}" -type f -name '*.gz' -mtime +14 -delete
echo "[backup] 完成，已清理 14 天前的备份"
