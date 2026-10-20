#!/usr/bin/env bash
# init.sql 为本地开发环境创建了无密码的 localhost 账号。
# 容器部署统一使用官方镜像创建的 '%' 账号，这里把 localhost 账号也设置为同一强密码，
# 避免容器内出现无密码账号，同时保留升级脚本 GRANT 到 localhost 的兼容性。
set -Eeuo pipefail

mysql --protocol=socket -uroot -p"${MYSQL_ROOT_PASSWORD}" \
    -e "ALTER USER IF EXISTS 'jqgg_glm53_user'@'localhost' IDENTIFIED BY '${MYSQL_PASSWORD}';"

