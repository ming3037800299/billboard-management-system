package com.jqgg.glm53.service;

import com.jqgg.glm53.dto.DbColumnVO;
import com.jqgg.glm53.dto.DbOverviewVO;
import com.jqgg.glm53.dto.DbSearchHitVO;
import com.jqgg.glm53.dto.DbSearchResultVO;
import com.jqgg.glm53.dto.DbTableVO;
import com.jqgg.glm53.dto.FieldStatsVO;
import com.jqgg.glm53.exception.BadRequestException;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 数据库维护服务：
 *   1. 通过 JDBC 元数据 / information_schema 读取当前库的表、字段、注释与数据量；
 *   2. 支持按表名、字段名、注释以及表内数据内容检索，定位数据所在的表和字段；
 *   3. 为“广告牌字段 / 属性”子模块提供真实的 ALTER TABLE 能力。
 *
 * MySQL 下读取 information_schema 以获得完整中文注释；其他数据库（如测试用 H2）
 * 回退到 DatabaseMetaData，保证功能可测试、可移植。
 */
@Service
public class DatabaseMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(DatabaseMaintenanceService.class);

    /** 允许的表 / 字段标识符 */
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    /** 允许的动态列类型白名单（由 BillboardFieldService 生成，这里再做一次防线） */
    private static final Pattern COLUMN_TYPE = Pattern.compile(
            "^(VARCHAR\\(\\d{1,4}\\)|TEXT|INT|BIGINT|DECIMAL\\(\\d{1,2},\\d{1,2}\\)|DATE|DATETIME|TINYINT\\(1\\))$",
            Pattern.CASE_INSENSITIVE);

    /** 数据内容检索最多扫描的表数 */
    private static final int MAX_DATA_TABLES = 30;

    /** 单表最多参与匹配的列数 */
    private static final int MAX_DATA_COLUMNS = 25;

    /** 数据内容命中上限 */
    private static final int MAX_DATA_HITS = 80;

    /** 单表返回的匹配行数 */
    private static final int ROWS_PER_TABLE = 10;

    /** 大表跳过阈值（避免无索引 LIKE 全表扫描拖垮数据库） */
    private static final long MAX_SCAN_ROWS = 300_000L;

    /** 表 / 字段检索命中上限 */
    private static final int MAX_COLUMN_HITS = 300;

    /** 不参与数据检索的列（向量值体积大且无检索意义） */
    private static final Set<String> DATA_BLOCKLIST = Set.of("embedding");

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    /** 数据库产品名缓存（连接池复用，启动后基本不变） */
    private volatile Boolean mysqlCache;

    /** 物理列缓存：前台每 15 秒轮询列表，不能每次都扫描整库结构。 */
    private static final long COLUMN_CACHE_MILLIS = 30_000L;
    private final Map<String, ColumnCache> columnCache = new ConcurrentHashMap<>();

    private record ColumnCache(Set<String> columns, long cachedAt) {
    }

    public DatabaseMaintenanceService(@Qualifier("dataSource") DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.jdbcTemplate.setQueryTimeout(5);
    }

    // ==================== 概览 / 表结构 ====================

    /** 数据库概览与 DDL 权限状态。 */
    public DbOverviewVO overview() {
        SchemaSnapshot schema = loadSchema();
        boolean ddlAllowed = ddlAllowed();
        String hint = ddlAllowed
                ? "当前数据库账号具备 ALTER 权限，新增字段/属性会同步修改数据库表结构。"
                : "当前数据库账号缺少 ALTER 权限，新增字段/属性将无法修改表结构。请使用 root 执行："
                + " GRANT ALTER ON " + schema.databaseName() + ".* TO " + grantee() + ";";
        return new DbOverviewVO(
                schema.product(),
                schema.version(),
                schema.databaseName(),
                schema.schemaName(),
                schema.tables().size(),
                schema.columnCount(),
                ddlAllowed,
                hint);
    }

    /** 全部表结构；keyword 为空返回全部，否则按表名 / 注释 / 字段名 / 字段注释过滤。 */
    public List<DbTableVO> tables(String keyword) {
        SchemaSnapshot schema = loadSchema();
        String kw = normalizeKeyword(keyword);
        return schema.tables().stream()
                .filter(t -> kw == null || tableMatches(t, kw))
                .map(t -> toTableVO(schema.databaseName(), t))
                .toList();
    }

    /**
     * 数据库检索：
     *   - 表名 / 表注释 / 字段名 / 字段注释 / 字段类型命中；
     *   - 可选检索表内数据内容，返回命中行与行标识。
     */
    public DbSearchResultVO search(String keyword, boolean includeData) {
        String kw = normalizeKeyword(keyword);
        if (kw == null) {
            throw new BadRequestException("请输入要检索的表名、字段名、注释或数据内容");
        }
        SchemaSnapshot schema = loadSchema();
        String lower = kw.toLowerCase(Locale.ROOT);

        List<DbSearchHitVO> columnHits = searchSchema(schema, kw, lower);
        List<DbSearchHitVO> dataHits = List.of();
        boolean truncated = false;
        int scannedTables = 0;

        if (includeData) {
            DataSearchOutcome outcome = searchData(schema, kw, lower);
            dataHits = outcome.hits();
            truncated = outcome.truncated();
            scannedTables = outcome.scannedTables();
        }
        return new DbSearchResultVO(kw, includeData, columnHits, dataHits, truncated, scannedTables);
    }

    /** 当前账号是否具备 ALTER 权限（MySQL 通过 SHOW GRANTS 判断，其他数据库默认允许）。 */
    public boolean ddlAllowed() {
        if (!isMySql()) {
            return true;
        }
        try {
            List<String> grants = jdbcTemplate.queryForList("SHOW GRANTS FOR CURRENT_USER()", String.class);
            return grants.stream()
                    .map(g -> g.toUpperCase(Locale.ROOT))
                    .anyMatch(g -> g.contains("ALL PRIVILEGES") || g.contains("ALTER"));
        } catch (Exception e) {
            log.debug("读取数据库权限失败：{}", e.getMessage());
            return false;
        }
    }

    // ==================== DDL ====================

    /** billboard 表当前实际存在的列（小写），带短 TTL 缓存。 */
    public Set<String> existingColumns(String table) {
        if (table == null || table.isBlank()) {
            return Set.of();
        }
        long now = System.currentTimeMillis();
        ColumnCache cached = columnCache.get(table);
        if (cached != null && now - cached.cachedAt() < COLUMN_CACHE_MILLIS) {
            return cached.columns();
        }
        Set<String> columns = loadExistingColumns(table);
        columnCache.put(table, new ColumnCache(columns, now));
        return columns;
    }

    private Set<String> loadExistingColumns(String table) {
        try {
            if (isMySql()) {
                List<String> names = jdbcTemplate.queryForList(
                        "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
                        String.class, table);
                Set<String> set = new LinkedHashSet<>();
                names.forEach(name -> set.add(name.toLowerCase(Locale.ROOT)));
                return set;
            }
            return loadSchema().tables().stream()
                    .filter(t -> t.name().equalsIgnoreCase(table))
                    .findFirst()
                    .map(t -> {
                        Set<String> set = new LinkedHashSet<>();
                        t.columns().forEach(c -> set.add(c.name().toLowerCase(Locale.ROOT)));
                        return set;
                    })
                    .orElseGet(Set::of);
        } catch (Exception e) {
            log.debug("读取物理列失败，table={}：{}", table, e.getMessage());
            return Set.of();
        }
    }

    /** 字段结构变更后清空缓存，下一次读取立即拿到新列。 */
    public void invalidateSchemaCache() {
        columnCache.clear();
    }

    /** 判断物理列是否存在。 */
    public boolean columnExists(String table, String column) {
        return existingColumns(table).contains(column.toLowerCase(Locale.ROOT));
    }

    /**
     * 新增物理列。
     *
     * @param defaultLiteral 已转义的 SQL 默认值字面量（如 'abc' / 0 / NULL），可为 null
     */
    public void addColumn(String table, String column, String columnType, String comment, String defaultLiteral) {
        addColumn(table, column, columnType, comment, defaultLiteral, true);
    }

    /**
     * 新增物理列，可指定数据库可空性。
     * 非空列且携带默认值时通过 NOT NULL DEFAULT 完成；无默认值的非空列由调用方先回填空值再修改。
     */
    public void addColumn(String table, String column, String columnType, String comment,
                          String defaultLiteral, boolean nullable) {
        validateIdentifier(table, "表名");
        validateIdentifier(column, "字段名");
        if (columnType == null || !COLUMN_TYPE.matcher(columnType).matches()) {
            throw new BadRequestException("不支持的字段类型：" + columnType);
        }
        StringBuilder ddl = new StringBuilder()
                .append("ALTER TABLE ").append(quote(table))
                .append(" ADD COLUMN ").append(quote(column))
                .append(' ').append(columnType)
                .append(nullable ? " NULL" : " NOT NULL");
        if (defaultLiteral != null && !defaultLiteral.isBlank()) {
            ddl.append(" DEFAULT ").append(defaultLiteral);
        }
        if (isMySql() && comment != null && !comment.isBlank()) {
            ddl.append(" COMMENT ").append(sqlString(comment));
        }
        try {
            jdbcTemplate.execute(ddl.toString());
            if (!isMySql() && comment != null && !comment.isBlank()) {
                try {
                    jdbcTemplate.execute("COMMENT ON COLUMN " + quote(table) + "." + quote(column)
                            + " IS " + sqlString(comment));
                } catch (Exception e) {
                    // H2 等数据库不支持列注释时忽略，不影响字段本身
                    log.debug("写入列注释失败：{}", e.getMessage());
                }
            }
            invalidateSchemaCache();
        } catch (Exception e) {
            log.warn("新增字段失败，SQL={}，原因={}", ddl, e.getMessage());
            throw new BadRequestException("数据库新增字段失败：" + rootMessage(e)
                    + "。请确认数据库账号具备 ALTER 权限（见页面顶部提示）。");
        }
    }

    /** 删除物理列。 */
    public void dropColumn(String table, String column) {
        validateIdentifier(table, "表名");
        validateIdentifier(column, "字段名");
        if (!columnExists(table, column)) {
            return;
        }
        String ddl = "ALTER TABLE " + quote(table) + " DROP COLUMN " + quote(column);
        try {
            jdbcTemplate.execute(ddl);
            invalidateSchemaCache();
        } catch (Exception e) {
            log.warn("删除字段失败，SQL={}，原因={}", ddl, e.getMessage());
            throw new BadRequestException("数据库删除字段失败：" + rootMessage(e));
        }
    }

    /**
     * 修改物理列（重命名 / 类型 / 默认值 / 注释），供扩展字段编辑使用。
     * MySQL 通过 CHANGE COLUMN 完成，已有数据由数据库自动转换并保留。
     */
    public void changeColumn(String table, String oldColumn, String newColumn, String columnType,
                             String comment, String defaultLiteral, boolean nullable) {
        validateIdentifier(table, "表名");
        validateIdentifier(oldColumn, "原字段名");
        validateIdentifier(newColumn, "新字段名");
        if (columnType == null || !COLUMN_TYPE.matcher(columnType).matches()) {
            throw new BadRequestException("不支持的字段类型：" + columnType);
        }
        if (!isMySql()) {
            throw new BadRequestException("当前数据库暂不支持在线重命名字段或修改字段类型，请使用 MySQL 8");
        }
        if (!columnExists(table, oldColumn)) {
            throw new BadRequestException("数据库列不存在：" + oldColumn);
        }
        StringBuilder ddl = new StringBuilder()
                .append("ALTER TABLE ").append(quote(table))
                .append(" CHANGE COLUMN ").append(quote(oldColumn))
                .append(' ').append(quote(newColumn))
                .append(' ').append(columnType).append(nullable ? " NULL" : " NOT NULL");
        if (defaultLiteral != null && !defaultLiteral.isBlank()) {
            ddl.append(" DEFAULT ").append(defaultLiteral);
        }
        if (comment != null && !comment.isBlank()) {
            ddl.append(" COMMENT ").append(sqlString(comment));
        }
        try {
            jdbcTemplate.execute(ddl.toString());
            invalidateSchemaCache();
        } catch (Exception e) {
            log.warn("修改字段失败，SQL={}，原因={}", ddl, e.getMessage());
            throw new BadRequestException("数据库修改字段失败：" + rootMessage(e)
                    + "。请确认字段名未被占用，且数据可转换为目标类型。");
        }
    }

    /** 将列中的 NULL 值批量回填为指定 SQL 字面量（用于从可空改为非空）。 */
    public void fillNulls(String table, String column, String literal) {
        validateIdentifier(table, "表名");
        validateIdentifier(column, "字段名");
        String sql = "UPDATE " + quote(table) + " SET " + quote(column) + " = " + literal
                + " WHERE " + quote(column) + " IS NULL";
        try {
            jdbcTemplate.update(sql);
        } catch (Exception e) {
            log.warn("回填字段空值失败，SQL={}，原因={}", sql, e.getMessage());
            throw new BadRequestException("字段改为非空前回填空值失败：" + rootMessage(e));
        }
    }

    /**
     * 修改数据库列注释，并原样保留列类型、可空性、默认值、自增等定义。
     * MySQL 通过 SHOW CREATE TABLE 取出列定义后只替换 COMMENT 子句；
     * H2 等数据库使用 COMMENT ON COLUMN。
     */
    public void updateColumnComment(String table, String column, String comment) {
        validateIdentifier(table, "表名");
        validateIdentifier(column, "字段名");
        if (!columnExists(table, column)) {
            throw new BadRequestException("字段不存在：" + column);
        }
        try {
            if (isMySql()) {
                String createSql = jdbcTemplate.queryForObject(
                        "SHOW CREATE TABLE " + quote(table),
                        (rs, rowNum) -> rs.getString(2));
                String definition = findColumnDefinition(createSql, column);
                if (definition == null) {
                    throw new BadRequestException("未找到字段定义：" + column);
                }
                String rebuilt = stripColumnComment(definition);
                if (comment != null && !comment.isBlank()) {
                    rebuilt = rebuilt + " COMMENT " + sqlString(comment.trim());
                }
                jdbcTemplate.execute("ALTER TABLE " + quote(table) + " MODIFY COLUMN " + rebuilt);
            } else {
                String value = comment == null ? "" : comment.trim();
                jdbcTemplate.execute("COMMENT ON COLUMN " + quote(table) + "." + quote(column)
                        + " IS " + sqlString(value));
            }
            invalidateSchemaCache();
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            log.warn("修改字段注释失败，table={}，column={}，原因={}", table, column, e.getMessage());
            throw new BadRequestException("数据库字段注释修改失败：" + rootMessage(e));
        }
    }

    /** 从 SHOW CREATE TABLE 结果中取出某个字段的完整定义行。 */
    private String findColumnDefinition(String createSql, String column) {
        if (createSql == null) {
            return null;
        }
        String prefix = "`" + column + "` ";
        for (String line : createSql.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.regionMatches(true, 0, prefix, 0, prefix.length())) {
                if (trimmed.endsWith(",")) {
                    trimmed = trimmed.substring(0, trimmed.length() - 1);
                }
                return trimmed;
            }
        }
        return null;
    }

    /** 去掉列定义末尾的 COMMENT 子句（COMMENT 始终位于列定义最后）。 */
    private String stripColumnComment(String definition) {
        int idx = definition.toUpperCase(Locale.ROOT).indexOf(" COMMENT ");
        return idx >= 0 ? definition.substring(0, idx) : definition;
    }

    /**
     * 字段数据概况：总记录、非空、空值、不同值、示例值。
     * 用于在修改类型 / 可空性前评估影响。
     */
    public FieldStatsVO fieldStats(String table, String column) {
        validateIdentifier(table, "表名");
        validateIdentifier(column, "字段名");
        if (!columnExists(table, column)) {
            return new FieldStatsVO(0, 0, 0, 0, List.of());
        }
        String tableRef = quote(table);
        String columnRef = quote(column);
        try {
            long[] counts = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) AS total_rows, COUNT(" + columnRef + ") AS non_null_rows, "
                            + "COUNT(DISTINCT " + columnRef + ") AS distinct_rows FROM " + tableRef,
                    (rs, rowNum) -> new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3)});
            long total = counts == null ? 0 : counts[0];
            long nonNull = counts == null ? 0 : counts[1];
            long distinct = counts == null ? 0 : counts[2];
            List<String> samples = jdbcTemplate.query(
                    "SELECT " + columnRef + " FROM " + tableRef
                            + " WHERE " + columnRef + " IS NOT NULL LIMIT 5",
                    (rs, rowNum) -> {
                        Object value = rs.getObject(1);
                        if (value == null) {
                            return null;
                        }
                        String text = String.valueOf(value);
                        return text.length() > 80 ? text.substring(0, 80) + "…" : text;
                    });
            List<String> filtered = new ArrayList<>();
            for (String sample : samples) {
                if (sample != null && !sample.isBlank()) {
                    filtered.add(sample);
                }
            }
            return new FieldStatsVO(total, nonNull, Math.max(0, total - nonNull), distinct, filtered);
        } catch (Exception e) {
            log.debug("读取字段统计失败，{}.{}：{}", table, column, e.getMessage());
            return new FieldStatsVO(0, 0, 0, 0, List.of());
        }
    }

    /** 当前数据库名称（MySQL 为 catalog）。 */
    public String databaseName() {
        return loadSchema().databaseName();
    }

    /** 标识符加引号（MySQL 反引号 / 其他数据库双引号）。 */
    public String quote(String identifier) {
        validateIdentifier(identifier, "标识符");
        return isMySql() ? "`" + identifier + "`" : "\"" + identifier + "\"";
    }

    // ==================== 内部：模式读取 ====================

    private SchemaSnapshot loadSchema() {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData md = conn.getMetaData();
            String product = md.getDatabaseProductName();
            String version = md.getDatabaseProductVersion();
            String catalog = safe(conn.getCatalog());
            String schema = safe(conn.getSchema());
            boolean mysql = product != null && product.toLowerCase(Locale.ROOT).contains("mysql");
            String database = mysql ? catalog : (schema.isBlank() ? catalog : schema);
            List<SchemaTable> tables = mysql
                    ? loadMySqlSchema(database)
                    : loadJdbcSchema(conn, catalog, schema);
            int columnCount = tables.stream().mapToInt(t -> t.columns().size()).sum();
            return new SchemaSnapshot(product, version, database, schema, tables, columnCount);
        } catch (SQLException e) {
            throw new IllegalStateException("读取数据库结构失败：" + e.getMessage(), e);
        }
    }

    private List<SchemaTable> loadMySqlSchema(String database) {
        String sql = """
                SELECT t.TABLE_NAME,
                       COALESCE(t.TABLE_COMMENT, '') AS TABLE_COMMENT,
                       COALESCE(t.TABLE_ROWS, 0) AS TABLE_ROWS,
                       c.COLUMN_NAME,
                       c.COLUMN_TYPE,
                       c.IS_NULLABLE,
                       c.COLUMN_DEFAULT,
                       c.COLUMN_KEY,
                       c.EXTRA,
                       COALESCE(c.COLUMN_COMMENT, '') AS COLUMN_COMMENT
                FROM information_schema.TABLES t
                JOIN information_schema.COLUMNS c
                  ON c.TABLE_SCHEMA = t.TABLE_SCHEMA AND c.TABLE_NAME = t.TABLE_NAME
                WHERE t.TABLE_SCHEMA = ? AND t.TABLE_TYPE = 'BASE TABLE'
                ORDER BY t.TABLE_NAME, c.ORDINAL_POSITION
                """;
        Map<String, List<SchemaColumn>> columns = new LinkedHashMap<>();
        Map<String, String> comments = new HashMap<>();
        Map<String, Long> rows = new HashMap<>();
        jdbcTemplate.query(sql, rs -> {
            String table = rs.getString("TABLE_NAME");
            columns.computeIfAbsent(table, k -> new ArrayList<>());
            comments.putIfAbsent(table, rs.getString("TABLE_COMMENT"));
            rows.putIfAbsent(table, rs.getLong("TABLE_ROWS"));
            String key = rs.getString("COLUMN_KEY");
            columns.get(table).add(new SchemaColumn(
                    rs.getString("COLUMN_NAME"),
                    rs.getString("COLUMN_TYPE"),
                    rs.getString("COLUMN_COMMENT"),
                    "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE")),
                    rs.getString("COLUMN_DEFAULT"),
                    key,
                    rs.getString("EXTRA"),
                    "PRI".equalsIgnoreCase(key)));
        }, database);
        List<SchemaTable> tables = new ArrayList<>();
        columns.forEach((table, cols) -> tables.add(new SchemaTable(
                table,
                comments.getOrDefault(table, ""),
                rows.getOrDefault(table, 0L),
                List.copyOf(cols))));
        return tables;
    }

    private List<SchemaTable> loadJdbcSchema(Connection conn, String catalog, String schema) throws SQLException {
        DatabaseMetaData md = conn.getMetaData();
        List<SchemaTable> tables = new ArrayList<>();
        try (ResultSet trs = md.getTables(catalog, blankToNull(schema), "%", new String[]{"TABLE"})) {
            while (trs.next()) {
                String table = trs.getString("TABLE_NAME");
                if (table == null || table.toLowerCase(Locale.ROOT).startsWith("information_schema")) {
                    continue;
                }
                String tableComment = trs.getString("REMARKS");
                List<SchemaColumn> cols = new ArrayList<>();
                try (ResultSet crs = md.getColumns(catalog, blankToNull(schema), table, "%")) {
                    while (crs.next()) {
                        String column = crs.getString("COLUMN_NAME");
                        String typeName = crs.getString("TYPE_NAME");
                        int size = crs.getInt("COLUMN_SIZE");
                        int decimals = crs.getInt("DECIMAL_DIGITS");
                        boolean nullable = crs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls;
                        String defaultValue = crs.getString("COLUMN_DEF");
                        String remark = crs.getString("REMARKS");
                        cols.add(new SchemaColumn(
                                column,
                                formatJdbcType(typeName, size, decimals),
                                remark,
                                nullable,
                                defaultValue,
                                null,
                                null,
                                false));
                    }
                }
                Set<String> primaryKeys = new LinkedHashSet<>();
                try (ResultSet pk = md.getPrimaryKeys(catalog, blankToNull(schema), table)) {
                    while (pk.next()) {
                        primaryKeys.add(pk.getString("COLUMN_NAME"));
                    }
                }
                if (!primaryKeys.isEmpty()) {
                    cols = cols.stream()
                            .map(c -> new SchemaColumn(c.name(), c.type(), c.comment(), c.nullable(),
                                    c.defaultValue(), "PRI", c.extra(), primaryKeys.contains(c.name())))
                            .toList();
                }
                long rowCount;
                try {
                    Long count = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM " + (isMySql() ? "`" + table + "`" : "\"" + table + "\""),
                            Long.class);
                    rowCount = count == null ? 0 : count;
                } catch (Exception e) {
                    rowCount = -1;
                }
                tables.add(new SchemaTable(table, tableComment == null ? "" : tableComment, rowCount, cols));
            }
        }
        tables.sort(Comparator.comparing(SchemaTable::name, String.CASE_INSENSITIVE_ORDER));
        return tables;
    }

    private String formatJdbcType(String typeName, int size, int decimals) {
        if (typeName == null) {
            return "";
        }
        String upper = typeName.toUpperCase(Locale.ROOT);
        if (upper.contains("CHAR") && size > 0) {
            return upper + "(" + size + ")";
        }
        if ((upper.contains("DECIMAL") || upper.contains("NUMERIC")) && size > 0) {
            return upper + "(" + size + "," + Math.max(decimals, 0) + ")";
        }
        if (upper.contains("BINARY") || upper.contains("VARBINARY")) {
            return upper + "(" + size + ")";
        }
        return upper;
    }

    private DbTableVO toTableVO(String database, SchemaTable table) {
        List<DbColumnVO> columns = table.columns().stream()
                .map(c -> new DbColumnVO(c.name(), c.type(), c.comment(), c.nullable(),
                        c.defaultValue(), c.key(), c.extra(), c.primaryKey()))
                .toList();
        return new DbTableVO(database, table.name(), table.comment(), table.rows(), columns);
    }

    // ==================== 内部：检索 ====================

    private boolean tableMatches(SchemaTable table, String keyword) {
        String lower = keyword.toLowerCase(Locale.ROOT);
        if (contains(table.name(), lower) || contains(table.comment(), lower)) {
            return true;
        }
        return table.columns().stream()
                .anyMatch(c -> contains(c.name(), lower)
                        || contains(c.comment(), lower)
                        || contains(c.type(), lower));
    }

    private List<DbSearchHitVO> searchSchema(SchemaSnapshot schema, String keyword, String lower) {
        Map<String, DbSearchHitVO> hits = new LinkedHashMap<>();
        for (SchemaTable table : schema.tables()) {
            boolean tableNameHit = contains(table.name(), lower);
            boolean tableCommentHit = contains(table.comment(), lower);
            for (SchemaColumn column : table.columns()) {
                List<String> types = new ArrayList<>(2);
                if (tableNameHit) {
                    types.add("表名");
                }
                if (tableCommentHit) {
                    types.add("表注释");
                }
                if (contains(column.name(), lower)) {
                    types.add("字段名");
                }
                if (contains(column.comment(), lower)) {
                    types.add("字段注释");
                }
                if (contains(column.type(), lower)) {
                    types.add("字段类型");
                }
                if (types.isEmpty()) {
                    continue;
                }
                String key = table.name() + "|" + column.name();
                hits.putIfAbsent(key, new DbSearchHitVO(
                        schema.databaseName(),
                        table.name(),
                        table.comment(),
                        column.name(),
                        column.type(),
                        column.comment(),
                        String.join(" / ", types),
                        null,
                        null));
                if (hits.size() >= MAX_COLUMN_HITS) {
                    return new ArrayList<>(hits.values());
                }
            }
        }
        return new ArrayList<>(hits.values());
    }

    private DataSearchOutcome searchData(SchemaSnapshot schema, String keyword, String lower) {
        List<DbSearchHitVO> hits = new ArrayList<>();
        boolean truncated = false;
        int scanned = 0;

        List<SchemaTable> tables = schema.tables().stream()
                .sorted(Comparator.comparingLong((SchemaTable t) -> t.rows() < 0 ? Long.MAX_VALUE : t.rows())
                        .thenComparing(SchemaTable::name, String.CASE_INSENSITIVE_ORDER))
                .toList();

        for (SchemaTable table : tables) {
            if (scanned >= MAX_DATA_TABLES || hits.size() >= MAX_DATA_HITS) {
                truncated = true;
                break;
            }
            if (table.rows() > MAX_SCAN_ROWS) {
                truncated = true;
                continue;
            }
            List<SchemaColumn> candidates = table.columns().stream()
                    .filter(this::searchableColumn)
                    .limit(MAX_DATA_COLUMNS)
                    .toList();
            if (candidates.isEmpty()) {
                continue;
            }
            List<SchemaColumn> pkColumns = table.columns().stream()
                    .filter(SchemaColumn::primaryKey)
                    .toList();
            if (pkColumns.isEmpty()) {
                pkColumns = List.of(table.columns().get(0));
            }
            final List<SchemaColumn> primaryColumns = pkColumns;
            scanned++;

            String castType = isMySql() ? "CHAR" : "VARCHAR";
            StringBuilder sql = new StringBuilder("SELECT ");
            int alias = 0;
            for (SchemaColumn pk : pkColumns) {
                if (alias > 0) {
                    sql.append(", ");
                }
                sql.append(quote(pk.name())).append(" AS __pk").append(alias);
                alias++;
            }
            int pkCount = pkColumns.size();
            for (int i = 0; i < candidates.size(); i++) {
                if (sql.charAt(sql.length() - 1) != ' ') {
                    sql.append(", ");
                }
                sql.append("CAST(").append(quote(candidates.get(i).name())).append(" AS ")
                        .append(castType).append(") AS __c").append(i);
            }
            sql.append(" FROM ").append(quote(table.name())).append(" WHERE ");
            for (int i = 0; i < candidates.size(); i++) {
                if (i > 0) {
                    sql.append(" OR ");
                }
                sql.append("CAST(").append(quote(candidates.get(i).name())).append(" AS ")
                        .append(castType).append(") LIKE ?");
            }
            sql.append(" LIMIT ").append(ROWS_PER_TABLE);

            String like = "%" + escapeLike(keyword) + "%";
            Object[] args = new Object[candidates.size()];
            for (int i = 0; i < args.length; i++) {
                args[i] = like;
            }
            try {
                jdbcTemplate.query(sql.toString(), args, (RowCallbackHandler) rs -> {
                    if (hits.size() >= MAX_DATA_HITS) {
                        return;
                    }
                    String rowRef = buildRowRef(rs, primaryColumns, pkCount);
                    for (int i = 0; i < candidates.size() && hits.size() < MAX_DATA_HITS; i++) {
                        Object value = rs.getObject(pkCount + 1 + i);
                        if (value == null) {
                            continue;
                        }
                        String text = String.valueOf(value);
                        if (!text.toLowerCase(Locale.ROOT).contains(lower)) {
                            continue;
                        }
                        SchemaColumn column = candidates.get(i);
                        hits.add(new DbSearchHitVO(
                                schema.databaseName(),
                                table.name(),
                                table.comment(),
                                column.name(),
                                column.type(),
                                column.comment(),
                                "数据内容",
                                truncate(text, 300),
                                rowRef));
                    }
                });
            } catch (Exception e) {
                log.debug("数据检索跳过表 {}：{}", table.name(), e.getMessage());
            }
        }
        if (hits.size() >= MAX_DATA_HITS) {
            truncated = true;
        }
        return new DataSearchOutcome(hits, truncated, scanned);
    }

    private String buildRowRef(ResultSet rs, List<SchemaColumn> pkColumns, int pkCount) {
        StringBuilder ref = new StringBuilder();
        for (int i = 0; i < pkCount; i++) {
            if (i > 0) {
                ref.append(" / ");
            }
            try {
                ref.append(pkColumns.get(i).name()).append('=').append(rs.getObject(i + 1));
            } catch (SQLException e) {
                ref.append(pkColumns.get(i).name()).append("=?");
            }
        }
        return ref.toString();
    }

    private boolean searchableColumn(SchemaColumn column) {
        String name = column.name().toLowerCase(Locale.ROOT);
        if (DATA_BLOCKLIST.contains(name)) {
            return false;
        }
        String type = column.type() == null ? "" : column.type().toLowerCase(Locale.ROOT);
        if (type.contains("blob") || type.contains("binary") || type.contains("geometry")) {
            return false;
        }
        return type.contains("char") || type.contains("text") || type.contains("enum")
                || type.contains("set") || type.contains("int") || type.contains("decimal")
                || type.contains("numeric") || type.contains("double") || type.contains("float")
                || type.contains("date") || type.contains("time") || type.contains("bool")
                || type.contains("bit") || type.contains("json");
    }

    // ==================== 内部：工具 ====================

    private String currentUser() {
        try {
            String user = jdbcTemplate.queryForObject("SELECT CURRENT_USER()", String.class);
            return user == null ? "jqgg_glm53_user" : user;
        } catch (Exception e) {
            return "jqgg_glm53_user";
        }
    }

    /** 生成 GRANT 语句中的账号部分（CURRENT_USER() 形如 user@host）。 */
    private String grantee() {
        String current = currentUser();
        int at = current.indexOf('@');
        if (at > 0) {
            return "'" + current.substring(0, at) + "'@'" + current.substring(at + 1) + "'";
        }
        return "'" + current + "'";
    }

    /** 当前数据库是否为 MySQL（决定标识符引号、DDL 语法与字符串转义方式）。 */
    public boolean isMySql() {
        Boolean cached = mysqlCache;
        if (cached != null) {
            return cached;
        }
        boolean mysql;
        try (Connection conn = dataSource.getConnection()) {
            String product = conn.getMetaData().getDatabaseProductName();
            mysql = product != null && product.toLowerCase(Locale.ROOT).contains("mysql");
        } catch (SQLException e) {
            mysql = false;
        }
        mysqlCache = mysql;
        return mysql;
    }

    private void validateIdentifier(String value, String label) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new BadRequestException(label + "不合法：" + value);
        }
    }

    private String normalizeKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        String trimmed = keyword.trim();
        if (trimmed.length() > 100) {
            throw new BadRequestException("检索关键词不能超过 100 字");
        }
        return trimmed;
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private String sqlString(String value) {
        String escaped = value.replace("\0", "").replace("'", "''");
        if (isMySql()) {
            // MySQL 默认将反斜杠视为转义符，需再次转义，避免破坏字符串字面量
            escaped = escaped.replace("\\", "\\\\");
        }
        return "'" + escaped + "'";
    }

    private String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String message = cur.getMessage();
        if (message == null || message.isBlank()) {
            message = cur.getClass().getSimpleName();
        }
        return truncate(message, 300);
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private boolean contains(String value, String lowerKeyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(lowerKeyword);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    // ==================== 内部数据结构 ====================

    private record SchemaColumn(
            String name,
            String type,
            String comment,
            boolean nullable,
            String defaultValue,
            String key,
            String extra,
            boolean primaryKey) {
    }

    private record SchemaTable(
            String name,
            String comment,
            long rows,
            List<SchemaColumn> columns) {
    }

    private record SchemaSnapshot(
            String product,
            String version,
            String databaseName,
            String schemaName,
            List<SchemaTable> tables,
            int columnCount) {
    }

    private record DataSearchOutcome(
            List<DbSearchHitVO> hits,
            boolean truncated,
            int scannedTables) {
    }
}
