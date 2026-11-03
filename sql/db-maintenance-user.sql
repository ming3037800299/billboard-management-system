-- ============================================================
-- 可选：数据库维护模块的跨库维护账号
-- 用法（需 root 权限）：
--   mysql -u root -p --default-character-set=utf8mb4 < sql/db-maintenance-user.sql
--
-- 说明：
--   应用主账号 jqgg_glm53_user 只有 jqgg_glm53 库权限，数据库维护页默认只能看到该库。
--   如果需要“先列出服务器上的数据库，再进入某个库管理字段”，请使用本脚本创建一个
--   维护账号，并通过环境变量交给应用：
--     DB_MAINT_USERNAME=jqgg_maint_user
--     DB_MAINT_PASSWORD=（这里设置的密码）
--
-- 安全提示：
--   该账号被授予 SELECT、ALTER、SHOW DATABASES，可读取所有数据库数据并修改表结构，
--   仅建议在受控的开发 / 运维环境中使用，不要用于公开可访问的互联网部署。
--   生产环境建议改为按库授权，例如只授予目标库的 SELECT、ALTER。
-- ============================================================

CREATE USER IF NOT EXISTS 'jqgg_maint_user'@'localhost' IDENTIFIED BY '';

GRANT SELECT, ALTER, SHOW DATABASES ON *.* TO 'jqgg_maint_user'@'localhost';

FLUSH PRIVILEGES;

-- 查看维护账号可访问的数据库
SHOW GRANTS FOR 'jqgg_maint_user'@'localhost';
