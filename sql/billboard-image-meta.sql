-- ============================================================
-- 广告牌图片分类元信息升级脚本（可重复执行）
--   适用：已部署的旧数据库（billboard 表缺少 image_meta 列）
--   用途：压缩包批量上传 / 图片自动分类需要保存“分类 + 封面 + 原始文件名”
--   执行：mysql -u root -p --default-character-set=utf8mb4 < sql/billboard-image-meta.sql
--   说明：全新数据库直接执行 sql/init.sql 即已包含该列，无需再执行本脚本。
-- ============================================================

USE jqgg_glm53;

SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'billboard'
      AND COLUMN_NAME = 'image_meta'
);

SET @ddl := IF(
    @col_exists = 0,
    'ALTER TABLE billboard ADD COLUMN image_meta TEXT NULL COMMENT ''图片元信息（JSON 数组：url/分类/是否封面/原始文件名）'' AFTER images',
    'SELECT ''image_meta 已存在，跳过'' AS message'
);

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
