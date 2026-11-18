-- ============================================================
-- 公路广告牌租赁定位管理系统（初版） 数据库初始化脚本
-- 适用：MySQL 8.0+
-- 用法：mysql -u root -p --default-character-set=utf8mb4 < sql/init.sql
-- 说明：
--   1. 自动创建数据库 jqgg_glm53 与核心表 billboard
--   2. 创建应用专用最小权限账号 jqgg_glm53_user（仅本库增删改查权限，无 DDL）
--   3. 脚本幂等，可重复执行：建库/建表/建用户/种子数据均不产生副作用
-- ============================================================

-- Docker MySQL 初始化入口不会自动加 --default-character-set；
-- 必须在导入中文种子数据前显式声明客户端字符集，否则会双重编码。
SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS jqgg_glm53 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE jqgg_glm53;

-- ------------------------------------------------------------
-- 核心数据表：billboard（公路广告牌信息表）
-- 字段覆盖前台展示与后台编辑的全部数据项
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS billboard (
    id           BIGINT        NOT NULL AUTO_INCREMENT         COMMENT '主键ID（唯一自增）',
    name         VARCHAR(100)  NOT NULL                        COMMENT '广告牌名称',
    code         VARCHAR(50)   NOT NULL                        COMMENT '广告牌编号（唯一标识）',
    road         VARCHAR(100)  DEFAULT NULL                    COMMENT '所在公路（如 京沪高速G2）',
    section      VARCHAR(200)  DEFAULT NULL                    COMMENT '路段位置（如 保定段 K1123+500）',
    address      VARCHAR(500)  DEFAULT NULL                    COMMENT '详细地址',
    longitude    DECIMAL(10,6) DEFAULT NULL                    COMMENT '经度（GCJ-02 火星坐标系，用于地图定位）',
    latitude     DECIMAL(10,6) DEFAULT NULL                    COMMENT '纬度（GCJ-02 火星坐标系，用于地图定位）',
    images       TEXT                                          COMMENT '广告牌图片路径（JSON 数组字符串，支持多图）',
    image_meta   TEXT                                          COMMENT '图片元信息（JSON 数组：url/分类/是否封面/原始文件名）',
    price        DECIMAL(12,2) DEFAULT NULL                    COMMENT '租赁单价（对应 price_unit 计费周期）',
    price_unit   VARCHAR(20)   DEFAULT '元/月'                 COMMENT '计费周期：元/月、元/年',
    price_note   VARCHAR(500)  DEFAULT NULL                    COMMENT '报价说明',
    size         VARCHAR(100)  DEFAULT NULL                    COMMENT '尺寸（如 18m×6m）',
    material     VARCHAR(100)  DEFAULT NULL                    COMMENT '材质（如 钢结构高炮）',
    visible_area VARCHAR(100)  DEFAULT NULL                    COMMENT '可视面积',
    audience     VARCHAR(500)  DEFAULT NULL                    COMMENT '受众范围',
    status       VARCHAR(20)   NOT NULL DEFAULT 'AVAILABLE'    COMMENT '租赁状态：AVAILABLE可租赁 / RENTED已租赁 / PAUSED暂停出租',
    description  TEXT                                          COMMENT '详细简介（优势、适配行业等备注）',
    deleted      TINYINT(1)    NOT NULL DEFAULT 0              COMMENT '软删除标记：0正常 / 1已删除',
    created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（自动记录）',
    updated_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（自动记录）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_code (code),
    KEY idx_status (status),
    KEY idx_road (road)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '公路广告牌信息表';

-- ------------------------------------------------------------
-- 应用专用账号：
--   授予 jqgg_glm53 库内 SELECT/INSERT/UPDATE/DELETE 权限，
--   以及 ALTER 权限（数据库维护模块新增广告牌字段/属性时需要修改表结构），
--   无其他库权限。本机采用无密码策略（密码为空），
--   如需密码，修改下方 IDENTIFIED BY '' 并用环境变量 DB_PASSWORD 覆盖。
-- ------------------------------------------------------------
CREATE USER IF NOT EXISTS 'jqgg_glm53_user'@'localhost' IDENTIFIED BY '';
GRANT SELECT, INSERT, UPDATE, DELETE, ALTER ON jqgg_glm53.* TO 'jqgg_glm53_user'@'localhost';
FLUSH PRIVILEGES;

-- ------------------------------------------------------------
-- 种子数据：29 条示例广告牌（8 条基础 + 14 条扩充 + 7 条新疆昌吉，坐标为演示用近似值，GCJ-02）
-- 精确坐标可通过腾讯拾取坐标系统获取：https://lbs.qq.com/getPoint/
-- 使用 ON DUPLICATE KEY UPDATE id = id 保证脚本可重复执行
-- ------------------------------------------------------------
INSERT INTO billboard
    (name, code, road, section, address, longitude, latitude, images, price, price_unit, price_note,
     size, material, visible_area, audience, status, description)
VALUES
    ('京沪高速保定南段高炮广告牌', 'JH-BB-001', '京沪高速G2', '保定南段 K1123+500',
     '河北省保定市清苑区 京沪高速与保沧高速交汇处以北 300 米',
     115.464600, 38.748900, '["/img/samples/bb-01.svg","/img/samples/bb-02.svg"]',
     26000.00, '元/月', '含基础电费与夜间亮化，不含画面制作安装费',
     '18m×6m', '钢结构单立柱高炮', '108㎡（双面展示）',
     '日均车流约 9 万辆，覆盖京津冀物流干线与进城商务人群', 'AVAILABLE',
     '位于京沪高速保定南段，临近雄安新区方向分流节点，双向车流密集，适合物流、汽车、区域商业品牌投放。'),

    ('京沪高速沧州北互通跨线桥广告牌', 'JH-BB-002', '京沪高速G2', '沧州北互通 K1198+200',
     '河北省沧州市新华区 沧保公路与京沪高速交汇处',
     116.867200, 38.318400, '["/img/samples/bb-03.svg"]',
     39000.00, '元/月', '枢纽黄金点位，三面展示，含亮化电费',
     '42m×5m', '钢结构跨线桥', '630㎡（三面环绕）',
     '日均车流约 11 万辆，覆盖环渤海产业带通勤与货运人群', 'AVAILABLE',
     '沧州北互通为京沪高速与石黄高速交汇枢纽，视野开阔无遮挡，远距离即可辨识，适合品牌形象类大面积投放。'),

    ('沈海高速太仓段单立柱广告牌', 'SH-BB-001', '沈海高速G15', '太仓段 K1265+800',
     '江苏省苏州市太仓市 陆渡镇沈海高速东侧',
     121.133500, 31.545700, '["/img/samples/bb-04.svg","/img/samples/bb-05.svg"]',
     32000.00, '元/月', '双面展示，可按季度支付',
     '21m×7m', '钢结构单立柱高炮', '147㎡（双面展示）',
     '日均车流约 8 万辆，沪苏通勤与长三角城际物流人群', 'RENTED',
     '紧邻上海北部入城方向，苏沪通勤主干道，晚间亮化效果好。当前已整年租出，到期后可续谈。'),

    ('沪昆高速杭州绕城南段广告牌', 'HK-BB-001', '沪昆高速G60', '杭州绕城南段 K1765+100',
     '浙江省杭州市萧山区 义桥镇沪昆高速与绕城南线交汇处',
     120.216800, 30.079200, '["/img/samples/bb-02.svg","/img/samples/bb-06.svg"]',
     52000.00, '元/月', '含审批与年检代办，签三年以上可优惠 8%',
     '24m×8m', '钢结构单立柱高炮', '192㎡（双面展示）',
     '日均车流约 13 万辆，覆盖杭州都市圈中高端消费人群', 'AVAILABLE',
     '杭州绕城南线与沪昆高速交汇节点，连接萧山机场与主城区方向，周边高端楼盘与产业园区密集，适合汽车、金融、地产类品牌。'),

    ('长深高速南京六合段广告牌', 'CS-BB-001', '长深高速G25', '六合段 K1987+600',
     '江苏省南京市六合区 金牛湖街道长深高速西侧',
     119.012400, 32.531800, '["/img/samples/bb-05.svg"]',
     24000.00, '元/月', '单面展示，含结构年检',
     '18m×6m', '钢结构单立柱高炮', '108㎡（单面展示）',
     '日均车流约 6.5 万辆，苏皖省际干线货运与通勤人群', 'AVAILABLE',
     '长深高速南京北向出城主干道，车流以货运与省际通行为主，适合物流园区、装备制造类广告投放。'),

    ('京港澳高速郑州东段广告牌', 'JG-BB-001', '京港澳高速G4', '郑州东段 K672+300',
     '河南省郑州市中牟县 郑开大道与京港澳高速交汇处以南',
     114.028900, 34.783600, '["/img/samples/bb-06.svg","/img/samples/bb-01.svg"]',
     380000.00, '元/年', '按年计费，含画面一次免费更换',
     '20m×7m', '钢结构单立柱高炮', '140㎡（双面展示）',
     '日均车流约 10 万辆，覆盖中原城市群核心通勤走廊', 'RENTED',
     '京港澳与连霍高速交汇的郑州枢纽东侧，货运与客运混行，视野良好。当前租约执行中，有意向可预约下一档期。'),

    ('连霍高速西安灞桥段广告牌', 'LH-BB-001', '连霍高速G30', '西安灞桥段 K1042+900',
     '陕西省西安市灞桥区 灞桥街道连霍高速与绕城高速交汇处东侧',
     109.093800, 34.286500, '["/img/samples/bb-03.svg","/img/samples/bb-04.svg"]',
     290000.00, '元/年', '按年计费，含夜间亮化与基础维护',
     '18m×6m', '钢结构单立柱高炮', '108㎡（双面展示）',
     '日均车流约 7.8 万辆，覆盖关中平原城际与旅游客流', 'AVAILABLE',
     '西安东向进出城门户位置，连接兵马俑、华清池旅游线路，节假日车流显著放大，适合文旅、白酒、汽车类品牌。'),

    ('济广高速合肥北段广告牌', 'JG-BB-002', '济广高速G35', '合肥北段 K1218+400',
     '安徽省合肥市长丰县 双墩镇济广高速与绕城北环交汇处',
     117.171200, 31.960400, '["/img/samples/bb-01.svg"]',
     21000.00, '元/月', '结构新装两年，含首年维护',
     '18m×6m', '钢结构单立柱高炮', '108㎡（双面展示）',
     '日均车流约 5.6 万辆，覆盖合肥北部新城通勤与货运人群', 'PAUSED',
     '合肥北绕城节点，车流平稳。因路段规划调整暂停出租，恢复时间另行通知，可留言登记意向。')
ON DUPLICATE KEY UPDATE id = id;

-- ------------------------------------------------------------
-- 知识库向量表：每条广告牌对应一条知识文档
-- embedding 为向量 JSON 数组；内容或模型变化时由应用重建
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS billboard_knowledge (
    billboard_id  BIGINT       NOT NULL                        COMMENT '广告牌ID（与 billboard.id 对应）',
    content       MEDIUMTEXT   NOT NULL                        COMMENT '用于向量化的知识文本',
    content_hash  CHAR(64)     NOT NULL                        COMMENT '内容哈希（内容或模型变化时重建）',
    embedding     MEDIUMTEXT                                  COMMENT '向量 JSON 数组',
    dimension     INT          DEFAULT NULL                    COMMENT '向量维度',
    model         VARCHAR(100) DEFAULT NULL                    COMMENT '向量模型名称',
    status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING'     COMMENT '索引状态：PENDING/READY/ERROR',
    error_message VARCHAR(500) DEFAULT NULL                    COMMENT '最近一次索引错误信息',
    indexed_at    DATETIME     DEFAULT NULL                    COMMENT '最近成功索引时间',
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (billboard_id),
    KEY idx_knowledge_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '广告牌知识库向量表';

-- ------------------------------------------------------------
-- 扩充演示数据：14 条广告牌（每条公路补足 3 块以上，便于查看公路分布）
-- 与上一条 INSERT 一样使用 ON DUPLICATE KEY UPDATE 保证幂等
-- ------------------------------------------------------------
INSERT INTO billboard
    (name, code, road, section, address, longitude, latitude, images, price, price_unit, price_note,
     size, material, visible_area, audience, status, description)
VALUES
    ('京沪高速天津静海段广告牌', 'JH-BB-003', '京沪高速G2', '天津静海段 K1080+600',
     '天津市静海区 静海镇京沪高速与荣乌高速互通以北',
     116.974300, 38.947600, '["/img/samples/bb-02.svg","/img/samples/bb-03.svg"]',
     42000.00, '元/月', '含亮化电费，画面制作另计',
     '20m×7m', '钢结构单立柱高炮', '140㎡（双面展示）',
     '日均车流约 10 万辆，京津通勤与环渤海物流人群', 'AVAILABLE',
     '位于京沪高速天津静海段，连接天津市区与静海产业区，双向车流稳定，适合汽车、家居、区域商业品牌。'),

    ('京沪高速德州南段广告牌', 'JH-BB-004', '京沪高速G2', '德州南段 K1050+200',
     '山东省德州市陵城区 京沪高速与滨德高速交汇处以南',
     116.357500, 37.435000, '["/img/samples/bb-04.svg"]',
     35000.00, '元/月', '双面展示，按季度支付',
     '18m×6m', '钢结构单立柱高炮', '108㎡（双面展示）',
     '日均车流约 8.5 万辆，鲁西北货运与京津冀通勤人群', 'RENTED',
     '京沪高速德州南段主线点位，货车占比高，视野开阔。当前租约执行中，可预约下一档期。'),

    ('沈海高速常熟段广告牌', 'SH-BB-002', '沈海高速G15', '常熟段 K1218+900',
     '江苏省苏州市常熟市 梅李镇沈海高速与常嘉高速交汇处',
     120.823400, 31.720600, '["/img/samples/bb-01.svg","/img/samples/bb-05.svg"]',
     28000.00, '元/月', '含首次画面制作，不含电费',
     '18m×6m', '钢结构单立柱高炮', '108㎡（双面展示）',
     '日均车流约 7.2 万辆，苏南制造业与沪苏通勤人群', 'AVAILABLE',
     '常熟段连接苏州北部工业带与沿江港口，货车与通勤车流混行，适合制造业、物流与区域零售品牌。'),

    ('沈海高速南通段广告牌', 'SH-BB-003', '沈海高速G15', '南通段 K1160+300',
     '江苏省南通市通州区 平潮镇沈海高速与沪陕高速交汇处以北',
     120.894600, 32.105700, '["/img/samples/bb-06.svg"]',
     26000.00, '元/月', '单面展示，含结构年检',
     '21m×7m', '钢结构单立柱高炮', '147㎡（单面展示）',
     '日均车流约 6.8 万辆，苏中沿江通勤与货运人群', 'PAUSED',
     '南通段临近沪苏通长江大桥北接线，车流以过江通勤和货运为主。因画面改造暂停出租，预计下月恢复。'),

    ('沪昆高速嘉兴段广告牌', 'HK-BB-002', '沪昆高速G60', '嘉兴段 K1590+500',
     '浙江省嘉兴市秀洲区 王店镇沪昆高速与常台高速交汇处',
     120.746800, 30.767800, '["/img/samples/bb-02.svg","/img/samples/bb-04.svg"]',
     46000.00, '元/月', '含亮化与年检，签两年可优惠',
     '24m×8m', '钢结构单立柱高炮', '192㎡（双面展示）',
     '日均车流约 11 万辆，沪杭通勤与浙北产业带人群', 'AVAILABLE',
     '沪昆高速嘉兴段位于沪杭之间，连接嘉兴主城与王店物流园，适合汽车、金融、家居类品牌。'),

    ('沪昆高速金华段广告牌', 'HK-BB-003', '沪昆高速G60', '金华段 K1470+700',
     '浙江省金华市金东区 傅村镇沪昆高速与金丽温高速交汇处以东',
     119.647500, 29.112400, '["/img/samples/bb-05.svg"]',
     36000.00, '元/月', '双面展示，含基础维护',
     '18m×6m', '钢结构单立柱高炮', '108㎡（双面展示）',
     '日均车流约 8.9 万辆，浙中城市群与义乌商贸物流人群', 'RENTED',
     '金华段连接义乌国际商贸城与浙中城市群，货运车流密集，适合物流、商贸、快消品牌。当前已租，可登记下一档期。'),

    ('长深高速淮安段广告牌', 'CS-BB-002', '长深高速G25', '淮安段 K1840+200',
     '江苏省淮安市淮阴区 丁集镇长深高速与淮徐高速交汇处以北',
     119.015200, 33.551700, '["/img/samples/bb-03.svg"]',
     22000.00, '元/月', '单面展示，含结构年检',
     '18m×6m', '钢结构单立柱高炮', '108㎡（单面展示）',
     '日均车流约 5.9 万辆，苏北干线货运与城际通勤人群', 'AVAILABLE',
     '淮安段位于苏北交通枢纽，连接淮安主城与周边产业园，适合物流、农产品加工与区域品牌投放。'),

    ('长深高速连云港段广告牌', 'CS-BB-003', '长深高速G25', '连云港段 K1730+800',
     '江苏省连云港市海州区 宁海街道长深高速与连霍高速交汇处以南',
     119.221800, 34.596900, '["/img/samples/bb-01.svg","/img/samples/bb-06.svg"]',
     25000.00, '元/月', '双面展示，含夜间亮化',
     '20m×7m', '钢结构单立柱高炮', '140㎡（双面展示）',
     '日均车流约 6.4 万辆，港口物流与苏北沿海通勤人群', 'AVAILABLE',
     '连云港段临近港口与连霍高速起点，货运集散特征明显，适合港口服务、物流、装备制造类品牌。'),

    ('京港澳高速新乡段广告牌', 'JG-BB-003', '京港澳高速G4', '新乡段 K640+100',
     '河南省新乡市原阳县 京港澳高速与长济高速交汇处以北',
     113.926700, 35.303200, '["/img/samples/bb-04.svg","/img/samples/bb-02.svg"]',
     31000.00, '元/月', '双面展示，含首次画面制作',
     '21m×7m', '钢结构单立柱高炮', '147㎡（双面展示）',
     '日均车流约 7.6 万辆，中原城市群北部通勤与货运人群', 'AVAILABLE',
     '新乡段连接郑州都市圈与豫北产业带，车流稳定，适合汽车、食品、家居类品牌。'),

    ('京港澳高速许昌段广告牌', 'JG-BB-004', '京港澳高速G4', '许昌段 K720+500',
     '河南省许昌市建安区 京港澳高速与永登高速交汇处以南',
     113.852700, 34.035700, '["/img/samples/bb-05.svg"]',
     27000.00, '元/月', '单面展示，含基础维护',
     '18m×6m', '钢结构单立柱高炮', '108㎡（单面展示）',
     '日均车流约 6.1 万辆，许昌制造业与郑州通勤人群', 'PAUSED',
     '许昌段位于郑州与许昌之间，周边制造业园区密集。因结构检修暂停出租，恢复时间另行通知。'),

    ('连霍高速渭南段广告牌', 'LH-BB-002', '连霍高速G30', '渭南段 K990+300',
     '陕西省渭南市临渭区 连霍高速与榆蓝高速交汇处以东',
     109.937600, 34.499500, '["/img/samples/bb-03.svg","/img/samples/bb-06.svg"]',
     260000.00, '元/年', '按年计费，含亮化与年检',
     '24m×8m', '钢结构单立柱高炮', '192㎡（双面展示）',
     '日均车流约 7.1 万辆，关中平原东向货运与旅游客流', 'RENTED',
     '渭南段连接西安与华山旅游线路，节假日车流放大，适合文旅、汽车、白酒类品牌。当前租约执行中。'),

    ('连霍高速宝鸡段广告牌', 'LH-BB-003', '连霍高速G30', '宝鸡段 K860+200',
     '陕西省宝鸡市陈仓区 连霍高速与银昆高速交汇处以西',
     107.237300, 34.361500, '["/img/samples/bb-01.svg"]',
     240000.00, '元/年', '按年计费，含画面一次免费更换',
     '18m×6m', '钢结构单立柱高炮', '108㎡（双面展示）',
     '日均车流约 5.4 万辆，关中西部工业与陕甘省际通勤人群', 'AVAILABLE',
     '宝鸡段位于关中西部工业走廊，连接宝鸡主城与高新区，适合装备制造、汽车、区域商业品牌。'),

    ('济广高速阜阳段广告牌', 'JG-BB-005', '济广高速G35', '阜阳段 K950+700',
     '安徽省阜阳市颍泉区 济广高速与滁新高速交汇处以北',
     115.814200, 32.890700, '["/img/samples/bb-02.svg","/img/samples/bb-05.svg"]',
     20000.00, '元/月', '双面展示，含结构年检',
     '18m×6m', '钢结构单立柱高炮', '108㎡（双面展示）',
     '日均车流约 5.1 万辆，皖北人口大市与豫皖省际通勤人群', 'AVAILABLE',
     '阜阳段覆盖皖北人口密集区，返乡与省际通勤车流明显，适合快消、家电、区域零售品牌。'),

    ('济广高速济南段广告牌', 'JG-BB-006', '济广高速G35', '济南段 K1240+100',
     '山东省济南市历城区 济广高速与青银高速交汇处以南',
     117.034400, 36.681000, '["/img/samples/bb-04.svg","/img/samples/bb-06.svg"]',
     NULL, '元/月', '面议，含亮化与年检，具体按投放周期商定',
     '20m×7m', '钢结构单立柱高炮', '140㎡（双面展示）',
     '日均车流约 9.2 万辆，济南都市圈通勤与省会商务人群', 'AVAILABLE',
     '济南段位于省会东向出入口，连接济南主城与高新区，车流密集，适合金融、汽车、互联网与区域总部品牌。')
ON DUPLICATE KEY UPDATE id = id;

-- ------------------------------------------------------------
-- 新疆昌吉周边路牌样例：7 条（乌昌快速路 2 / 京新高速G7 3 / G312沪霍线 2）
-- 覆盖乌鲁木齐—昌吉、阜康—吉木萨尔—奇台、呼图壁—玛纳斯三个走廊
-- ------------------------------------------------------------
INSERT INTO billboard
    (name, code, road, section, address, longitude, latitude, images, price, price_unit, price_note,
     size, material, visible_area, audience, status, description)
VALUES
    ('乌昌快速路头屯河段单立柱广告牌', 'XJ-UC-001', '乌昌快速路', '头屯河段 K8+600',
     '新疆维吾尔自治区乌鲁木齐市头屯河区 乌昌快速路与祥云街交汇处',
     87.423500, 43.910800, '["/img/samples/bb-01.svg","/img/samples/bb-03.svg"]',
     22000.00, '元/月', '含亮化电费，画面制作另计',
     '18m×6m', '钢结构单立柱高炮', '108㎡（双面展示）',
     '日均车流约 7.5 万辆，乌昌同城通勤与机场方向商务人群', 'AVAILABLE',
     '位于乌昌快速路头屯河段，连接乌鲁木齐主城与昌吉市区，通勤车流稳定，适合汽车、房产、区域商业品牌。'),

    ('乌昌快速路昌吉滨湖段广告牌', 'XJ-UC-002', '乌昌快速路', '昌吉滨湖段 K18+200',
     '新疆维吾尔自治区昌吉回族自治州昌吉市 滨湖镇乌昌快速路与北绕城交汇处',
     87.342000, 44.012500, '["/img/samples/bb-02.svg","/img/samples/bb-05.svg"]',
     24000.00, '元/月', '双面展示，含结构年检',
     '20m×7m', '钢结构单立柱高炮', '140㎡（双面展示）',
     '日均车流约 6.8 万辆，昌吉滨湖新区与乌鲁木齐通勤人群', 'RENTED',
     '昌吉滨湖段临近昌吉北绕城与滨湖新区，视野开阔。当前租约执行中，可预约下一档期。'),

    ('京新高速G7阜康段高炮广告牌', 'XJ-G7-001', '京新高速G7', '阜康段 K520+800',
     '新疆维吾尔自治区昌吉回族自治州阜康市 京新高速与吐乌大高速交汇处以东',
     87.951200, 44.178300, '["/img/samples/bb-04.svg","/img/samples/bb-06.svg"]',
     28000.00, '元/月', '含亮化与年检，签两年可优惠',
     '21m×7m', '钢结构单立柱高炮', '147㎡（双面展示）',
     '日均车流约 8.2 万辆，天山天池旅游与准东能源基地通勤人群', 'AVAILABLE',
     '阜康段临近天山天池景区入口与准东经济技术开发区，旅游旺季车流放大，适合文旅、能源、汽车品牌。'),

    ('京新高速G7吉木萨尔段广告牌', 'XJ-G7-002', '京新高速G7', '吉木萨尔段 K610+500',
     '新疆维吾尔自治区昌吉回族自治州吉木萨尔县 京新高速与G216交汇处以北',
     89.112600, 44.053700, '["/img/samples/bb-03.svg"]',
     18000.00, '元/月', '单面展示，含基础维护',
     '18m×6m', '钢结构单立柱高炮', '108㎡（单面展示）',
     '日均车流约 5.6 万辆，准东能源基地货运与吉木萨尔县城通勤人群', 'AVAILABLE',
     '吉木萨尔段连接准东能源基地与县城，货运车辆占比高，适合能源装备、物流、农资类品牌。'),

    ('京新高速G7奇台段广告牌', 'XJ-G7-003', '京新高速G7', '奇台段 K660+300',
     '新疆维吾尔自治区昌吉回族自治州奇台县 京新高速与S228交汇处以南',
     89.548800, 44.041200, '["/img/samples/bb-05.svg","/img/samples/bb-02.svg"]',
     16000.00, '元/月', '双面展示，含结构年检',
     '18m×6m', '钢结构单立柱高炮', '108㎡（双面展示）',
     '日均车流约 4.8 万辆，奇台农业、江布拉克旅游与东三县通勤人群', 'PAUSED',
     '奇台段是昌吉东三县进入准东与乌鲁木齐方向的重要节点，因画面升级暂停出租，预计下月恢复。'),

    ('G312沪霍线呼图壁段广告牌', 'XJ-G312-001', 'G312沪霍线', '呼图壁段 K78+900',
     '新疆维吾尔自治区昌吉回族自治州呼图壁县 沪霍线与呼图壁河大桥交汇处',
     86.914600, 44.189200, '["/img/samples/bb-06.svg","/img/samples/bb-01.svg"]',
     17000.00, '元/月', '含首次画面制作，不含电费',
     '18m×6m', '钢结构单立柱高炮', '108㎡（双面展示）',
     '日均车流约 4.5 万辆，呼图壁纺织产业与昌吉西向货运人群', 'AVAILABLE',
     '呼图壁段位于昌吉向西通往石河子的国省道走廊，周边纺织与农产品加工企业集中，适合区域产业品牌。'),

    ('G312沪霍线玛纳斯段广告牌', 'XJ-G312-002', 'G312沪霍线', '玛纳斯段 K150+200',
     '新疆维吾尔自治区昌吉回族自治州玛纳斯县 沪霍线与玛纳斯收费站交汇处以西',
     86.238800, 44.307600, '["/img/samples/bb-04.svg"]',
     NULL, '元/月', '面议，含亮化与年检，具体按投放周期商定',
     '24m×8m', '钢结构单立柱高炮', '192㎡（双面展示）',
     '日均车流约 5.2 万辆，玛纳斯农业、石河子方向通勤与货运人群', 'AVAILABLE',
     '玛纳斯段连接昌吉西部农业区与石河子市区，路段视野开阔，适合农业机械、食品加工与区域零售品牌。')
ON DUPLICATE KEY UPDATE id = id;

-- ------------------------------------------------------------
-- 数据库维护模块：子模块与广告牌扩展字段定义
--   - sys_maintenance_module：后台“数据库维护”页可配置的多个子模块
--   - billboard_field：每个扩展字段对应 billboard 表中的一个真实物理列
--   应用启动时会自动补齐 4 个内置子模块（检索/表结构/广告牌字段/广告牌属性）
-- ------------------------------------------------------------
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
    enabled       TINYINT(1)   NOT NULL DEFAULT 1             COMMENT '是否启用：1启用 / 0停用（停用保留列与数据）',
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

-- ------------------------------------------------------------
-- 业务流水号计数表：合同号 / 预定号原子分配
-- 并发请求通过同一条记录的自增串行化，避免 count()+1 撞唯一键。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_sequence (
    seq_name  VARCHAR(64) NOT NULL COMMENT '序号名称，如 contract-20261118 / reservation-20261118',
    seq_value BIGINT      NOT NULL DEFAULT 0 COMMENT '当前已分配的最大序号',
    PRIMARY KEY (seq_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '业务流水号计数表';

-- ------------------------------------------------------------
-- API 管理表：向量模型 / 检索生成模型 / 地图服务 / 其他接口
-- 数据库中的启用配置优先于 application.yml；同一类型最多一个默认配置。
-- api_key 明文存储（后台运维数据），列表接口只返回脱敏值。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS api_config (
    id              BIGINT        NOT NULL AUTO_INCREMENT           COMMENT '主键ID',
    name            VARCHAR(100)  NOT NULL                          COMMENT '配置名称（全局唯一）',
    type            VARCHAR(30)   NOT NULL                          COMMENT '类型：EMBEDDING向量模型 / LLM检索生成 / MAP地图 / OTHER其他',
    provider        VARCHAR(30)   NOT NULL                          COMMENT '协议：OLLAMA / OPENAI / TENCENT / HTTP',
    base_url        VARCHAR(500)  DEFAULT NULL                      COMMENT '接口根地址',
    api_key         VARCHAR(1000) DEFAULT NULL                      COMMENT 'API Key / 地图AK（列表接口脱敏）',
    model           VARCHAR(200)  DEFAULT NULL                      COMMENT '模型名称',
    max_tokens      INT           DEFAULT NULL                      COMMENT '单次最大token数（LLM）',
    temperature     DECIMAL(4,2)  DEFAULT NULL                     COMMENT '采样温度 0~2（LLM）',
    timeout_seconds INT           NOT NULL DEFAULT 60               COMMENT '读取超时（秒）',
    enabled         TINYINT(1)    NOT NULL DEFAULT 1                COMMENT '是否启用：0停用 / 1启用',
    is_default      TINYINT(1)    NOT NULL DEFAULT 0                COMMENT '是否同类默认配置：0否 / 1是',
    remark          VARCHAR(500)  DEFAULT NULL                      COMMENT '备注',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_api_config_name (name),
    KEY idx_api_config_type (type, enabled),
    KEY idx_api_config_default (type, is_default)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '外部接口配置表';

-- ------------------------------------------------------------
-- 预定与合同模块（与 sql/contract-module.sql 保持一致）
-- ------------------------------------------------------------

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
