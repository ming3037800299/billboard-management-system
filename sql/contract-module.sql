-- ============================================================
-- 预定与合同模块（预览版）
-- 适用：MySQL 8.0+
-- 用法：mysql -u root -p --default-character-set=utf8mb4 < sql/contract-module.sql
-- 说明：脚本幂等，可重复执行；已有数据库升级只需执行本文件。
-- ============================================================

SET NAMES utf8mb4;
USE jqgg_glm53;

-- ------------------------------------------------------------
-- 甲方签约主体与签章配置（单行表，id 固定为 1）
-- 签章图片存放在私有目录，不通过 /uploads/** 对外暴露。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS contract_settings (
    id                    BIGINT       NOT NULL                       COMMENT '主键（固定为1）',
    party_a_name          VARCHAR(200) NOT NULL DEFAULT ''            COMMENT '甲方名称',
    party_a_credit_code   VARCHAR(50)  DEFAULT NULL                   COMMENT '统一社会信用代码',
    party_a_address       VARCHAR(500) DEFAULT NULL                   COMMENT '甲方地址',
    party_a_legal_rep     VARCHAR(100) DEFAULT NULL                   COMMENT '法定代表人',
    party_a_contact       VARCHAR(100) DEFAULT NULL                   COMMENT '联系人',
    party_a_phone         VARCHAR(50)  DEFAULT NULL                   COMMENT '联系电话',
    party_a_bank_name     VARCHAR(200) DEFAULT NULL                   COMMENT '开户行',
    party_a_bank_account  VARCHAR(100) DEFAULT NULL                   COMMENT '银行账号',
    seal_path             VARCHAR(500) DEFAULT NULL                   COMMENT '甲方签章图片（私有目录相对路径）',
    sign_link_ttl_days    INT          NOT NULL DEFAULT 7             COMMENT '乙方签署链接有效期（天）',
    updated_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '甲方签约主体与签章配置';

-- 首次初始化示例数据；已有配置不会被覆盖（ON DUPLICATE KEY UPDATE id = id）。
INSERT INTO contract_settings
    (id, party_a_name, party_a_credit_code, party_a_address, party_a_legal_rep,
     party_a_contact, party_a_phone, party_a_bank_name, party_a_bank_account, sign_link_ttl_days)
VALUES
    (1, '示例广告牌运营有限公司（请替换）', '91310000MA1FL0000X', '示例省示例市示例大道 1 号',
     '张三', '李四', '13800000000', '示例银行示例支行', '6222000000000000', 7)
ON DUPLICATE KEY UPDATE id = id;

-- ------------------------------------------------------------
-- Word 合同模板：后台可上传 .docx，模板中使用 {{字段}} 和 {{@图片字段}} 占位符
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS contract_template (
    id            BIGINT       NOT NULL AUTO_INCREMENT        COMMENT '主键ID',
    name          VARCHAR(200) NOT NULL                       COMMENT '模板名称',
    file_path     VARCHAR(500) NOT NULL                       COMMENT '模板文件（私有目录相对路径）',
    original_name VARCHAR(255) DEFAULT NULL                   COMMENT '上传时的原始文件名',
    placeholders  TEXT                                        COMMENT '识别到的占位符（换行分隔）',
    is_default    TINYINT(1)   NOT NULL DEFAULT 0             COMMENT '是否默认模板',
    enabled       TINYINT(1)   NOT NULL DEFAULT 1             COMMENT '是否启用',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_template_default (is_default, enabled)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '合同Word模板表';

-- ------------------------------------------------------------
-- 预定记录：保存广告牌信息快照，合同生成后不受广告牌后续编辑影响
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS reservation (
    id                    BIGINT        NOT NULL AUTO_INCREMENT       COMMENT '主键ID',
    reservation_no        VARCHAR(40)   NOT NULL                      COMMENT '预定编号（YD-YYYYMMDD-序号）',
    billboard_id          BIGINT        NOT NULL                      COMMENT '广告牌ID',
    billboard_code        VARCHAR(50)   DEFAULT NULL                  COMMENT '广告牌编号快照',
    billboard_name        VARCHAR(100)  DEFAULT NULL                  COMMENT '广告牌名称快照',
    billboard_road        VARCHAR(100)  DEFAULT NULL                  COMMENT '所在公路快照',
    billboard_section     VARCHAR(200)  DEFAULT NULL                  COMMENT '路段快照',
    billboard_address     VARCHAR(500)  DEFAULT NULL                  COMMENT '详细地址快照',
    billboard_size        VARCHAR(100)  DEFAULT NULL                  COMMENT '尺寸快照',
    billboard_material    VARCHAR(100)  DEFAULT NULL                  COMMENT '材质快照',
    billboard_visible_area VARCHAR(100) DEFAULT NULL                  COMMENT '可视面积快照',
    billboard_price       DECIMAL(12,2) DEFAULT NULL                  COMMENT '单价快照',
    billboard_price_unit  VARCHAR(20)   DEFAULT NULL                  COMMENT '计费周期快照',
    billboard_price_note  VARCHAR(500)  DEFAULT NULL                  COMMENT '报价说明快照',
    tenant_type           VARCHAR(20)   NOT NULL DEFAULT 'INDIVIDUAL' COMMENT '乙方类型：INDIVIDUAL个人 / COMPANY企业',
    tenant_name           VARCHAR(200)  NOT NULL                      COMMENT '乙方姓名或公司名称',
    tenant_id_number      VARCHAR(100)  DEFAULT NULL                  COMMENT '乙方身份证号 / 统一社会信用代码',
    tenant_address        VARCHAR(500)  DEFAULT NULL                  COMMENT '乙方地址',
    tenant_contact        VARCHAR(100)  DEFAULT NULL                  COMMENT '乙方联系人',
    tenant_phone          VARCHAR(50)   DEFAULT NULL                  COMMENT '乙方电话',
    tenant_email          VARCHAR(200)  DEFAULT NULL                  COMMENT '乙方邮箱',
    ad_content            TEXT                                        COMMENT '广告发布内容',
    start_date            DATE          NOT NULL                      COMMENT '投放开始日期',
    end_date              DATE          NOT NULL                      COMMENT '投放结束日期',
    lease_months          INT           NOT NULL DEFAULT 1            COMMENT '租赁月数（按 30 天折算，向上取整）',
    total_amount          DECIMAL(14,2) NOT NULL DEFAULT 0            COMMENT '合同总金额（元）',
    deposit_amount        DECIMAL(14,2) NOT NULL DEFAULT 0            COMMENT '定金金额（元）',
    payment_terms         VARCHAR(500)  DEFAULT NULL                  COMMENT '付款方式',
    special_terms         TEXT                                        COMMENT '特殊约定',
    remark                VARCHAR(500)  DEFAULT NULL                  COMMENT '内部备注',
    status                VARCHAR(20)   NOT NULL DEFAULT 'CONFIRMED'  COMMENT '状态：CONFIRMED已预定 / SIGNED已签约 / COMPLETED已完成 / CANCELLED已取消',
    cancelled_at          DATETIME      DEFAULT NULL                  COMMENT '取消时间',
    cancel_reason         VARCHAR(500)  DEFAULT NULL                  COMMENT '取消原因',
    created_at            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_reservation_no (reservation_no),
    KEY idx_reservation_billboard (billboard_id),
    KEY idx_reservation_status (status),
    KEY idx_reservation_tenant (tenant_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '广告牌预定记录表';

-- ------------------------------------------------------------
-- 合同：系统生成或线下回签上传；data_snapshot 保存生成时的字段快照
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS contract (
    id                    BIGINT        NOT NULL AUTO_INCREMENT       COMMENT '主键ID',
    contract_no           VARCHAR(40)   NOT NULL                      COMMENT '合同编号（HT-YYYYMMDD-序号）',
    reservation_id        BIGINT        NOT NULL                      COMMENT '关联预定ID',
    billboard_id          BIGINT        NOT NULL                      COMMENT '广告牌ID',
    template_id           BIGINT        DEFAULT NULL                  COMMENT '生成时使用的模板ID',
    source                VARCHAR(20)   NOT NULL DEFAULT 'GENERATED'  COMMENT '来源：GENERATED系统生成 / UPLOADED线下上传',
    status                VARCHAR(20)   NOT NULL DEFAULT 'PENDING_SIGN' COMMENT '状态：PENDING_SIGN待签署 / SIGNED已签署 / COMPLETED已完成 / VOID已作废',
    current_file_path     VARCHAR(500)  DEFAULT NULL                  COMMENT '当前合同文件（私有目录相对路径）',
    current_file_name     VARCHAR(255)  DEFAULT NULL                  COMMENT '当前合同文件名',
    signature_file_path   VARCHAR(500)  DEFAULT NULL                  COMMENT '乙方签名图片（私有目录相对路径）',
    data_snapshot         LONGTEXT                                    COMMENT '生成时的合同字段快照（JSON）',
    signer_name           VARCHAR(100)  DEFAULT NULL                  COMMENT '签署人姓名',
    signed_at             DATETIME      DEFAULT NULL                  COMMENT '签署时间',
    signer_ip             VARCHAR(64)   DEFAULT NULL                  COMMENT '签署IP',
    sign_token            VARCHAR(128)  DEFAULT NULL                  COMMENT '乙方签署令牌',
    sign_token_expires_at DATETIME      DEFAULT NULL                  COMMENT '签署令牌过期时间',
    sign_token_revoked    TINYINT(1)    NOT NULL DEFAULT 0            COMMENT '签署令牌是否作废',
    void_reason           VARCHAR(500)  DEFAULT NULL                  COMMENT '作废原因',
    version               INT           NOT NULL DEFAULT 1            COMMENT '合同版本号',
    completed_at          DATETIME      DEFAULT NULL                  COMMENT '完成时间',
    created_at            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_contract_no (contract_no),
    UNIQUE KEY uk_contract_token (sign_token),
    KEY idx_contract_reservation (reservation_id),
    KEY idx_contract_status (status),
    KEY idx_contract_billboard (billboard_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '广告牌租赁合同表';

-- ------------------------------------------------------------
-- 合同文件版本：模板副本 / 草稿 / 签名 / 最终件 / 回签上传件
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS contract_file (
    id            BIGINT       NOT NULL AUTO_INCREMENT        COMMENT '主键ID',
    contract_id   BIGINT       NOT NULL                       COMMENT '合同ID',
    file_type     VARCHAR(30)  NOT NULL                       COMMENT '类型：TEMPLATE_COPY/GENERATED/SIGNATURE/FINAL/UPLOADED',
    file_path     VARCHAR(500) NOT NULL                       COMMENT '文件（私有目录相对路径）',
    original_name VARCHAR(255) DEFAULT NULL                   COMMENT '原始文件名',
    file_size     BIGINT       NOT NULL DEFAULT 0             COMMENT '文件大小（字节）',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_contract_file_contract (contract_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '合同文件版本表';

-- ------------------------------------------------------------
-- 合同操作日志：预定创建、合同生成、签署、上传、作废、完成
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS contract_event (
    id             BIGINT       NOT NULL AUTO_INCREMENT        COMMENT '主键ID',
    contract_id    BIGINT       DEFAULT NULL                   COMMENT '合同ID（预定事件可为空）',
    reservation_id BIGINT       DEFAULT NULL                   COMMENT '预定ID',
    action         VARCHAR(50)  NOT NULL                       COMMENT '动作：RESERVATION_CREATED/CONTRACT_GENERATED/CONTRACT_SIGNED/CONTRACT_UPLOADED/CONTRACT_VOIDED/CONTRACT_COMPLETED/TOKEN_REFRESHED',
    actor_type     VARCHAR(20)  NOT NULL DEFAULT 'ADMIN'       COMMENT '操作方：ADMIN/TENANT/SYSTEM',
    detail         VARCHAR(1000) DEFAULT NULL                  COMMENT '说明',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_contract_event_contract (contract_id, created_at),
    KEY idx_contract_event_reservation (reservation_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '合同操作日志表';
