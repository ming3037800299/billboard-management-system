-- ============================================================
-- 数据库维护模块迁移脚本（已有部署升级用）
-- 适用：MySQL 8.0+
-- 用法（需 root 权限，执行一次即可；脚本幂等）：
--   mysql -u root -p --default-character-set=utf8mb4 < sql/db-maintenance.sql
--
-- 作用：
--   1. 创建数据库维护子模块表 sys_maintenance_module
--   2. 创建广告牌扩展字段定义表 billboard_field
--   3. 为应用账号补充 ALTER 权限（新增字段时执行 ALTER TABLE 需要）
--   4. 补齐 4 个内置子模块（应用启动时也会自动补齐）
-- ============================================================

-- 避免 Docker / 非交互客户端按 latin1 导入中文注释和子模块名称
SET NAMES utf8mb4;

USE jqgg_glm53;

CREATE TABLE IF NOT EXISTS sys_maintenance_module (
    id           BIGINT       NOT NULL AUTO_INCREMENT         COMMENT '主键ID',
    name         VARCHAR(100) NOT NULL                        COMMENT '子模块名称',
    code         VARCHAR(50)  NOT NULL                        COMMENT '模块标识（唯一）',
    type         VARCHAR(30)  NOT NULL                        COMMENT '类型：SEARCH/SCHEMA/BILLBOARD_FIELD/BILLBOARD_ATTRIBUTE',
    description  VARCHAR(500) DEFAULT NULL                    COMMENT '子模块说明',
    sort_order   INT          NOT NULL DEFAULT 0              COMMENT '排序号（升序）',
    enabled      TINYINT(1)   NOT NULL DEFAULT 1              COMMENT '是否启用：1启用 / 0停用',
    builtin      TINYINT(1)   NOT NULL DEFAULT 0              COMMENT '是否内置：1内置不可删除 / 0自定义',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_module_code (code),
    KEY idx_module_sort (sort_order)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '数据库维护子模块配置表';

CREATE TABLE IF NOT EXISTS billboard_field (
    id            BIGINT       NOT NULL AUTO_INCREMENT        COMMENT '主键ID',
    module_id     BIGINT       NOT NULL                       COMMENT '所属子模块ID（sys_maintenance_module.id）',
    field_name    VARCHAR(64)  NOT NULL                       COMMENT 'billboard 表物理列名（唯一）',
    label         VARCHAR(100) NOT NULL                       COMMENT '字段显示名称',
    input_type    VARCHAR(20)  NOT NULL                       COMMENT '输入类型：TEXT/TEXTAREA/INTEGER/DECIMAL/DATE/DATETIME/SELECT/BOOLEAN',
    column_type   VARCHAR(50)  NOT NULL                       COMMENT '物理列类型（如 VARCHAR(200)）',
    field_length  INT          DEFAULT NULL                   COMMENT '文本长度',
    decimals      INT          DEFAULT NULL                   COMMENT '小数位数',
    required      TINYINT(1)   NOT NULL DEFAULT 0             COMMENT '是否必填（应用层校验）',
    nullable      TINYINT(1)   NOT NULL DEFAULT 1             COMMENT '是否允许数据库 NULL：1可空 / 0非空',
    enabled       TINYINT(1)   NOT NULL DEFAULT 1             COMMENT '是否启用：1启用 / 0停用',
    section       VARCHAR(30)  NOT NULL DEFAULT 'extension'   COMMENT '表单分区：basic/location/price/params/extension',
    width         VARCHAR(10)  NOT NULL DEFAULT 'half'        COMMENT '表单栏宽：half/full',
    default_value VARCHAR(500) DEFAULT NULL                   COMMENT '默认值',
    options_text  TEXT                                        COMMENT '下拉选项（换行分隔）',
    placeholder   VARCHAR(200) DEFAULT NULL                   COMMENT '表单提示文字',
    sort_order    INT          NOT NULL DEFAULT 0             COMMENT '排序号（升序）',
    visible       TINYINT(1)   NOT NULL DEFAULT 1             COMMENT '是否在前台详情展示',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_field_name (field_name),
    KEY idx_field_module (module_id, sort_order)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '广告牌扩展字段定义表';

-- 应用账号需要 ALTER 权限才能新增/删除广告牌扩展字段列
GRANT SELECT, INSERT, UPDATE, DELETE, ALTER ON jqgg_glm53.* TO 'jqgg_glm53_user'@'localhost';
FLUSH PRIVILEGES;

-- 内置子模块（唯一键 code，重复执行无副作用）
INSERT INTO sys_maintenance_module (name, code, type, description, sort_order, enabled, builtin)
VALUES
    ('数据库检索', 'db_search', 'SEARCH',
     '按表名、字段名、中文注释或表内数据内容，查询数据所在的数据库表与字段。', 10, 1, 1),
    ('数据表结构', 'schema_browser', 'SCHEMA',
     '浏览当前数据库全部表、字段类型、注释、主键与数据量。', 20, 1, 1),
    ('广告牌字段', 'billboard_fields', 'BILLBOARD_FIELD',
     '查看 billboard 表已有字段并维护扩展字段；新增字段同步 ALTER TABLE，编辑注释同步数据库。', 30, 1, 1),
    ('广告牌属性', 'billboard_attributes', 'BILLBOARD_ATTRIBUTE',
     '查看广告牌已有属性并维护扩展属性（下拉、日期、开关等）；新增与注释修改同步数据库。', 40, 1, 1),
    ('字段管理', 'field_manager', 'FIELD_MANAGER',
     '统一管理字段启用/停用、表单分区、栏宽、排序，支持新增和删除扩展字段。', 50, 1, 1),
    ('表单设计', 'form_designer', 'FORM_DESIGNER',
     '按表单分区放置扩展字段，调整栏宽和顺序，并预览表单效果。', 60, 1, 1)
ON DUPLICATE KEY UPDATE id = id;


-- ------------------------------------------------------------
-- 兼容已有部署：billboard_field 增加“数据库可空性”列（幂等）
-- ------------------------------------------------------------
SET @nullable_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'billboard_field' AND COLUMN_NAME = 'nullable'
);
SET @nullable_sql = IF(
    @nullable_exists = 0,
    'ALTER TABLE billboard_field ADD COLUMN nullable TINYINT(1) NOT NULL DEFAULT 1 COMMENT ''是否允许数据库 NULL：1可空 / 0非空'' AFTER required',
    'SELECT 1'
);
PREPARE nullable_stmt FROM @nullable_sql;
EXECUTE nullable_stmt;
DEALLOCATE PREPARE nullable_stmt;


-- ------------------------------------------------------------
-- 兼容已有部署：字段启用 / 表单分区 / 栏宽（幂等）
-- ------------------------------------------------------------
SET @field_layout_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'billboard_field' AND COLUMN_NAME = 'enabled');
SET @field_layout_sql = IF(@field_layout_exists = 0,
    'ALTER TABLE billboard_field ADD COLUMN enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT ''是否启用：1启用 / 0停用'' AFTER nullable',
    'SELECT 1');
PREPARE field_layout_stmt FROM @field_layout_sql;
EXECUTE field_layout_stmt;
DEALLOCATE PREPARE field_layout_stmt;

SET @field_section_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'billboard_field' AND COLUMN_NAME = 'section');
SET @field_section_sql = IF(@field_section_exists = 0,
    'ALTER TABLE billboard_field ADD COLUMN section VARCHAR(30) NOT NULL DEFAULT ''extension'' COMMENT ''表单分区：basic/location/price/params/extension'' AFTER enabled',
    'SELECT 1');
PREPARE field_section_stmt FROM @field_section_sql;
EXECUTE field_section_stmt;
DEALLOCATE PREPARE field_section_stmt;

SET @field_width_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'billboard_field' AND COLUMN_NAME = 'width');
SET @field_width_sql = IF(@field_width_exists = 0,
    'ALTER TABLE billboard_field ADD COLUMN width VARCHAR(10) NOT NULL DEFAULT ''half'' COMMENT ''表单栏宽：half/full'' AFTER section',
    'SELECT 1');
PREPARE field_width_stmt FROM @field_width_sql;
EXECUTE field_width_stmt;
DEALLOCATE PREPARE field_width_stmt;
