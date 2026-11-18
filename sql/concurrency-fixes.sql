-- ============================================================
-- 并发与数据一致性修复迁移脚本（可重复执行）
--   适用：已部署的旧数据库
--   内容：
--     1. sys_sequence：合同号 / 预定号原子分配计数表
--     2. 为合同、预定补充并发查询需要的普通索引（不改变已有数据）
--   执行：mysql -u root -p --default-character-set=utf8mb4 < sql/concurrency-fixes.sql
-- ============================================================

USE jqgg_glm53;

CREATE TABLE IF NOT EXISTS sys_sequence (
    seq_name  VARCHAR(64) NOT NULL COMMENT '序号名称，如 contract-20261118 / reservation-20261118',
    seq_value BIGINT      NOT NULL DEFAULT 0 COMMENT '当前已分配的最大序号',
    PRIMARY KEY (seq_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '业务流水号计数表';

-- 合同按预定查有效合同、到期任务按状态 + 预定查询
SET @idx_contract_reservation := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'contract'
      AND INDEX_NAME = 'idx_contract_reservation_status'
);
SET @ddl := IF(@idx_contract_reservation = 0,
    'ALTER TABLE contract ADD KEY idx_contract_reservation_status (reservation_id, status)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 预定按广告牌 + 状态查询（释放广告牌前的活动预定判断）
SET @idx_reservation_billboard := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'reservation'
      AND INDEX_NAME = 'idx_reservation_billboard_status'
);
SET @ddl2 := IF(@idx_reservation_billboard = 0,
    'ALTER TABLE reservation ADD KEY idx_reservation_billboard_status (billboard_id, status)',
    'SELECT 1');
PREPARE stmt2 FROM @ddl2;
EXECUTE stmt2;
DEALLOCATE PREPARE stmt2;
