package com.jqgg.glm53.service;

import com.jqgg.glm53.dto.DbColumnVO;
import com.jqgg.glm53.dto.DbDatabaseListVO;
import com.jqgg.glm53.dto.DbDatabaseVO;
import com.jqgg.glm53.dto.DbOverviewVO;
import com.jqgg.glm53.dto.DbSearchHitVO;
import com.jqgg.glm53.dto.DbSearchResultVO;
import com.jqgg.glm53.dto.DbTableVO;
import com.jqgg.glm53.config.AppProperties;
import com.jqgg.glm53.dto.TableColumnForm;
import com.jqgg.glm53.exception.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
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
 * 多数据库维护服务（使用独立的维护数据源）：
 *   1. 列出维护账号可见的数据库；
 *   2. 按数据库读取表 / 字段结构，支持跨库字段检索；
 *   3. 在任意数据库的任意表上新增字段（billboard 仍走专用字段子模块）。
 *
 * 该服务与应用业务数据源分离，未配置维护账号时回退到主数据源，
 * 因此不会因为跨库查询影响主业务的连接池。
 */
@Service
public class MultiDatabaseMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(MultiDatabaseMaintenanceService.class);

    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final Pattern COLUMN_TYPE = Pattern.compile(
            "^(VARCHAR\\(\\d{1,4}\\)|TEXT|INT|BIGINT|DECIMAL\\(\\d{1,2},\\d{1,2}\\)|DATE|DATETIME|TINYINT\\(1\\))$",
            Pattern.CASE_INSENSITIVE);

    private static final Set<String> SYSTEM_DATABASES = Set.of(
            "information_schema", "mysql", "performance_schema", "sys");

    private static final Set<String> DATA_BLOCKLIST = Set.of("embedding");
    private static final int MAX_DATA_TABLES = 30;
    private static final int MAX_DATA_COLUMNS = 25;
    private static final int MAX_DATA_HITS = 80;
    private static final int ROWS_PER_TABLE = 10;
    private static final long MAX_SCAN_ROWS = 300_000L;
    private static final int MAX_COLUMN_HITS = 300;
    private static final int MAX_ALL_DATABASES = 20;
    private static final long CACHE_MILLIS = 15_000L;

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final Map<String, CachedTables> tableCache = new ConcurrentHashMap<>();

    private record CachedTables(List<DbTableVO> tables, long cachedAt) {
    }

    public MultiDatabaseMaintenanceService(@Qualifier("dataSource") DataSource primaryDataSource,
                                           AppProperties appProperties,
                                           Environment environment) {
        this.dataSource = resolveDataSource(primaryDataSource, appProperties, environment);
        this.jdbcTemplate = new JdbcTemplate(this.dataSource);
        this.jdbcTemplate.setQueryTimeout(5);
    }

    /**
     * 配置了独立维护账号时使用独立连接；否则复用主数据源。
     * 这里不注册额外的 DataSource Bean，避免干扰 Spring Boot 的 DataSource 初始化顺序。
     */
    private static DataSource resolveDataSource(DataSource primaryDataSource,
                                                AppProperties appProperties,
                                                Environment environment) {
        AppProperties.DbMaintenance config = appProperties.getDbMaintenance();
        if (!config.isEnabled()
                || config.getUsername() == null
                || config.getUsername().isBlank()) {
            return primaryDataSource;
        }
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        String url = config.getUrl() == null || config.getUrl().isBlank()
                ? environment.getProperty("spring.datasource.url")
                : config.getUrl();
        dataSource.setUrl(url);
        dataSource.setUsername(config.getUsername());
        dataSource.setPassword(config.getPassword() == null ? "" : config.getPassword());
        String driver = environment.getProperty("spring.datasource.driver-class-name");
        if (driver != null && !driver.isBlank()) {
            dataSource.setDriverClassName(driver);
        }
        return dataSource;
    }

    // ==================== 数据库列表 / 概览 ====================

    /** 列出维护账号可见的业务数据库。 */
    public DbDatabaseListVO databases() {
        String current = defaultDatabase();
        List<DbDatabaseVO> list = new ArrayList<>();

        if (isMySql()) {
            List<String> names = jdbcTemplate.queryForList("SHOW DATABASES", String.class).stream()
                    .filter(name -> name != null && !SYSTEM_DATABASES.contains(name.toLowerCase(Locale.ROOT)))
                    .distinct()
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
            Map<String, long[]> counts = loadDatabaseCounts();
            for (String name : names) {
                long[] count = counts.getOrDefault(name, new long[]{0, 0});
                list.add(new DbDatabaseVO(name, count[0], count[1], name.equalsIgnoreCase(current)));
            }
            if (list.stream().noneMatch(db -> db.name().equalsIgnoreCase(current))) {
                int tableCount;
                int columnCount;
                try {
                    List<DbTableVO> tables = loadTables(current);
                    tableCount = tables.size();
                    columnCount = tables.stream().mapToInt(t -> t.columns().size()).sum();
                } catch (Exception e) {
                    tableCount = 0;
                    columnCount = 0;
                }
                list.add(0, new DbDatabaseVO(current, tableCount, columnCount, true));
            }
        } else {
            List<DbTableVO> tables = loadTables(current);
            list.add(new DbDatabaseVO(current, tables.size(),
                    tables.stream().mapToInt(t -> t.columns().size()).sum(), true));
        }
        return new DbDatabaseListVO(current, list);
    }

    /** 某个数据库的概览与 DDL 权限。 */
    public DbOverviewVO overview(String database) {
        String db = requireDatabase(database);
        List<DbTableVO> tables = loadTables(db);
        int columnCount = tables.stream().mapToInt(t -> t.columns().size()).sum();
        boolean ddlAllowed = ddlAllowed();
        String hint = ddlAllowed
                ? "当前维护账号具备 ALTER 权限，可直接在选中的库/表中新增字段。"
                : "当前维护账号缺少 ALTER 权限，新增字段会失败。请为该账号授予目标库的 ALTER 权限。";
        String product = "";
        String version = "";
        String schema = "";
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData md = conn.getMetaData();
            product = md.getDatabaseProductName();
            version = md.getDatabaseProductVersion();
            schema = safe(conn.getSchema());
        } catch (SQLException e) {
            log.debug("读取数据库产品信息失败：{}", e.getMessage());
        }
        return new DbOverviewVO(product, version, db, schema, tables.size(), columnCount, ddlAllowed, hint);
    }

    /** 列出某个数据库的全部表结构。 */
    public List<DbTableVO> tables(String database, String keyword) {
        String db = requireDatabase(database);
        String kw = normalizeKeyword(keyword);
        return loadTables(db).stream()
                .filter(t -> kw == null || tableMatches(t, kw))
                .toList();
    }

    // ==================== 检索 ====================

    /** 在指定数据库内检索表 / 字段 / 注释，可选扫描表内数据。 */
    public DbSearchResultVO search(String database, String keyword, boolean includeData) {
        String db = requireDatabase(database);
        String kw = normalizeKeyword(keyword);
        if (kw == null) {
            throw new BadRequestException("请输入要检索的表名、字段名、注释或数据内容");
        }
        List<DbTableVO> tables = loadTables(db);
        List<DbSearchHitVO> columnHits = searchSchema(db, tables, kw);
        List<DbSearchHitVO> dataHits = List.of();
        boolean truncated = false;
        int scannedTables = 0;
        if (includeData) {
            DataSearchOutcome outcome = searchData(db, tables, kw);
            dataHits = outcome.hits();
            truncated = outcome.truncated();
            scannedTables = outcome.scannedTables();
        }
        return new DbSearchResultVO(kw, includeData, columnHits, dataHits, truncated, scannedTables);
    }

    /** 跨数据库检索字段（不扫描表内数据），用于查看字段所属库。 */
    public DbSearchResultVO searchAll(String keyword) {
        String kw = normalizeKeyword(keyword);
        if (kw == null) {
            throw new BadRequestException("请输入要检索的表名、字段名或注释");
        }
        List<DbSearchHitVO> hits = new ArrayList<>();
        List<DbDatabaseVO> databases = databases().databases().stream()
                .limit(MAX_ALL_DATABASES)
                .toList();
        for (DbDatabaseVO database : databases) {
            if (hits.size() >= MAX_COLUMN_HITS) {
                break;
            }
            try {
                List<DbTableVO> tables = loadTables(database.name());
                for (DbSearchHitVO hit : searchSchema(database.name(), tables, kw)) {
                    hits.add(hit);
                    if (hits.size() >= MAX_COLUMN_HITS) {
                        break;
                    }
                }
            } catch (Exception e) {
                log.debug("跨库检索跳过 {}：{}", database.name(), e.getMessage());
            }
        }
        return new DbSearchResultVO(kw, false, hits, List.of(), false, databases.size());
    }

    private List<DbSearchHitVO> searchSchema(String database, List<DbTableVO> tables, String keyword) {
        String lower = keyword.toLowerCase(Locale.ROOT);
        Map<String, DbSearchHitVO> hits = new LinkedHashMap<>();
        for (DbTableVO table : tables) {
            boolean tableNameHit = contains(table.name(), lower);
            boolean tableCommentHit = contains(table.comment(), lower);
            for (DbColumnVO column : table.columns()) {
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
                        database,
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

    private DataSearchOutcome searchData(String database, List<DbTableVO> tables, String keyword) {
        String lower = keyword.toLowerCase(Locale.ROOT);
        List<DbSearchHitVO> hits = new ArrayList<>();
        boolean truncated = false;
        int scanned = 0;

        List<DbTableVO> sorted = tables.stream()
                .sorted(Comparator.comparingLong((DbTableVO t) -> t.rows() < 0 ? Long.MAX_VALUE : t.rows())
                        .thenComparing(DbTableVO::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
        for (DbTableVO table : sorted) {
            if (scanned >= MAX_DATA_TABLES || hits.size() >= MAX_DATA_HITS) {
                truncated = true;
                break;
            }
            if (table.rows() > MAX_SCAN_ROWS) {
                truncated = true;
                continue;
            }
            List<DbColumnVO> candidates = table.columns().stream()
                    .filter(this::searchableColumn)
                    .limit(MAX_DATA_COLUMNS)
                    .toList();
            if (candidates.isEmpty()) {
                continue;
            }
            List<DbColumnVO> pkColumns = table.columns().stream()
                    .filter(DbColumnVO::primaryKey)
                    .toList();
            if (pkColumns.isEmpty()) {
                pkColumns = List.of(table.columns().get(0));
            }
            final List<DbColumnVO> primaryColumns = pkColumns;
            scanned++;

            String castType = isMySql() ? "CHAR" : "VARCHAR";
            String tableRef = qualifiedTable(database, table.name());
            StringBuilder sql = new StringBuilder("SELECT ");
            int alias = 0;
            for (DbColumnVO pk : primaryColumns) {
                if (alias > 0) {
                    sql.append(", ");
                }
                sql.append(quote(pk.name())).append(" AS __pk").append(alias);
                alias++;
            }
            int pkCount = primaryColumns.size();
            for (int i = 0; i < candidates.size(); i++) {
                sql.append(", CAST(").append(quote(candidates.get(i).name())).append(" AS ")
                        .append(castType).append(") AS __c").append(i);
            }
            sql.append(" FROM ").append(tableRef).append(" WHERE ");
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
                        DbColumnVO column = candidates.get(i);
                        hits.add(new DbSearchHitVO(
                                database,
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
                log.debug("数据检索跳过 {}.{}：{}", database, table.name(), e.getMessage());
            }
        }
        if (hits.size() >= MAX_DATA_HITS) {
            truncated = true;
        }
        return new DataSearchOutcome(hits, truncated, scanned);
    }

    private String buildRowRef(ResultSet rs, List<DbColumnVO> pkColumns, int pkCount) {
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

    private boolean searchableColumn(DbColumnVO column) {
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

    // ==================== DDL：任意表新增字段 ====================

    /** 在指定数据库的表中新增字段，返回该库最新的表结构。 */
    public List<DbTableVO> addColumn(String database, String table, TableColumnForm form) {
        String db = requireDatabase(database);
        validateIdentifier(table, "表名");
        List<DbTableVO> tables = loadTables(db, true);
        DbTableVO target = tables.stream()
                .filter(t -> t.name().equalsIgnoreCase(table))
                .findFirst()
                .orElseThrow(() -> new BadRequestException("数据表不存在：" + db + "." + table));
        if (db.equalsIgnoreCase(defaultDatabase()) && target.name().equalsIgnoreCase("billboard")) {
            throw new BadRequestException("billboard 表字段请使用「广告牌字段 / 广告牌属性」子模块新增，以便同步到后台表单和前台展示");
        }

        String column = form.getFieldName().trim();
        validateIdentifier(column, "字段名");
        boolean exists = target.columns().stream()
                .anyMatch(c -> c.name().equalsIgnoreCase(column));
        if (exists) {
            throw new BadRequestException("字段已存在：" + table + "." + column);
        }

        ColumnSpec spec = resolveColumnSpec(form);
        String comment = form.getComment() == null ? "" : form.getComment().trim();
        String tableRef = qualifiedTable(db, table);

        StringBuilder ddl = new StringBuilder()
                .append("ALTER TABLE ").append(tableRef)
                .append(" ADD COLUMN ").append(quote(column))
                .append(' ').append(spec.columnType())
                .append(spec.nullable() ? " NULL" : " NOT NULL");
        if (spec.defaultLiteral() != null) {
            ddl.append(" DEFAULT ").append(spec.defaultLiteral());
        }
        if (isMySql() && !comment.isBlank()) {
            ddl.append(" COMMENT ").append(sqlString(comment));
        }
        try {
            jdbcTemplate.execute(ddl.toString());
            if (!isMySql() && !comment.isBlank()) {
                try {
                    jdbcTemplate.execute("COMMENT ON COLUMN " + tableRef + "." + quote(column)
                            + " IS " + sqlString(comment));
                } catch (Exception e) {
                    log.debug("写入列注释失败：{}", e.getMessage());
                }
            }
            tableCache.remove(db);
            return loadTables(db);
        } catch (Exception e) {
            log.warn("新增字段失败，SQL={}，原因={}", ddl, e.getMessage());
            throw new BadRequestException("数据库新增字段失败：" + rootMessage(e)
                    + "。请确认维护账号对 " + db + " 具备 ALTER 权限。");
        }
    }

    private ColumnSpec resolveColumnSpec(TableColumnForm form) {
        String type = form.getType() == null ? "" : form.getType().trim().toUpperCase(Locale.ROOT);
        boolean nullable = form.getNullable() == null || form.getNullable();
        String defaultValue = form.getDefaultValue() == null ? null : form.getDefaultValue().trim();
        if (defaultValue != null && defaultValue.isEmpty()) {
            defaultValue = null;
        }
        if (!nullable && defaultValue == null) {
            throw new BadRequestException("非空字段需要填写默认值，避免存量数据加列失败");
        }

        String columnType;
        switch (type) {
            case "VARCHAR" -> {
                int length = form.getLength() == null ? 200 : form.getLength();
                if (length < 1 || length > 2000) {
                    throw new BadRequestException("VARCHAR 长度需在 1 - 2000 之间");
                }
                columnType = "VARCHAR(" + length + ")";
            }
            case "TEXT" -> {
                columnType = "TEXT";
                if (defaultValue != null) {
                    throw new BadRequestException("TEXT 字段不支持数据库默认值");
                }
            }
            case "INT" -> columnType = "INT";
            case "BIGINT" -> columnType = "BIGINT";
            case "DECIMAL" -> {
                int precision = form.getLength() == null ? 18 : form.getLength();
                int scale = form.getDecimals() == null ? 2 : form.getDecimals();
                if (precision < 1 || precision > 65) {
                    throw new BadRequestException("DECIMAL 精度需在 1 - 65 之间");
                }
                if (scale < 0 || scale > 30 || scale > precision) {
                    throw new BadRequestException("DECIMAL 小数位需在 0 - 30 之间且不能大于精度");
                }
                columnType = "DECIMAL(" + precision + "," + scale + ")";
            }
            case "DATE" -> columnType = "DATE";
            case "DATETIME" -> columnType = "DATETIME";
            case "BOOLEAN", "TINYINT" -> columnType = "TINYINT(1)";
            default -> throw new BadRequestException("不支持的字段类型：" + form.getType());
        }
        if (!COLUMN_TYPE.matcher(columnType).matches()) {
            throw new BadRequestException("不支持的字段类型：" + columnType);
        }

        String defaultLiteral = null;
        if (defaultValue != null) {
            try {
                defaultLiteral = switch (type) {
                    case "VARCHAR" -> sqlString(defaultValue);
                    case "INT", "BIGINT" -> String.valueOf(Long.parseLong(defaultValue));
                    case "DECIMAL" -> new BigDecimal(defaultValue).toPlainString();
                    case "DATE" -> sqlString(LocalDate.parse(defaultValue).toString());
                    case "DATETIME" -> sqlString(parseDateTime(defaultValue)
                            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                    case "BOOLEAN", "TINYINT" -> parseBoolean(defaultValue) ? "1" : "0";
                    default -> null;
                };
            } catch (NumberFormatException e) {
                throw new BadRequestException("默认值必须是数字：" + defaultValue);
            } catch (DateTimeParseException e) {
                throw new BadRequestException("默认值日期格式不正确：" + defaultValue);
            }
        }
        return new ColumnSpec(columnType, nullable, defaultLiteral);
    }

    private LocalDateTime parseDateTime(String raw) {
        String value = raw.replace(' ', 'T');
        if (value.length() == 16) {
            value = value + ":00";
        }
        return LocalDateTime.parse(value);
    }

    private boolean parseBoolean(String raw) {
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "true", "1", "是", "yes", "y", "on" -> true;
            case "false", "0", "否", "no", "n", "off" -> false;
            default -> throw new BadRequestException("默认值请填写“是”或“否”");
        };
    }

    private record ColumnSpec(String columnType, boolean nullable, String defaultLiteral) {
    }

    // ==================== 内部：结构读取 ====================

    private List<DbTableVO> loadTables(String database) {
        return loadTables(database, false);
    }

    private List<DbTableVO> loadTables(String database, boolean force) {
        String db = requireDatabase(database);
        long now = System.currentTimeMillis();
        CachedTables cached = tableCache.get(db);
        if (!force && cached != null && now - cached.cachedAt() < CACHE_MILLIS) {
            return cached.tables();
        }
        List<DbTableVO> tables = isMySql() ? loadMySqlTables(db) : loadJdbcTables(db);
        tableCache.put(db, new CachedTables(tables, now));
        return tables;
    }

    private List<DbTableVO> loadMySqlTables(String database) {
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
        Map<String, List<DbColumnVO>> columns = new LinkedHashMap<>();
        Map<String, String> comments = new HashMap<>();
        Map<String, Long> rows = new HashMap<>();
        jdbcTemplate.query(sql, rs -> {
            String table = rs.getString("TABLE_NAME");
            columns.computeIfAbsent(table, k -> new ArrayList<>());
            comments.putIfAbsent(table, rs.getString("TABLE_COMMENT"));
            rows.putIfAbsent(table, rs.getLong("TABLE_ROWS"));
            String key = rs.getString("COLUMN_KEY");
            columns.get(table).add(new DbColumnVO(
                    rs.getString("COLUMN_NAME"),
                    rs.getString("COLUMN_TYPE"),
                    rs.getString("COLUMN_COMMENT"),
                    "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE")),
                    rs.getString("COLUMN_DEFAULT"),
                    key,
                    rs.getString("EXTRA"),
                    "PRI".equalsIgnoreCase(key)));
        }, database);
        List<DbTableVO> tables = new ArrayList<>();
        columns.forEach((table, cols) -> tables.add(new DbTableVO(
                database,
                table,
                comments.getOrDefault(table, ""),
                rows.getOrDefault(table, 0L),
                List.copyOf(cols))));
        return tables;
    }

    private List<DbTableVO> loadJdbcTables(String database) {
        if (!database.equalsIgnoreCase(defaultDatabase())) {
            return List.of();
        }
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData md = conn.getMetaData();
            String catalog = safe(conn.getCatalog());
            String schema = safe(conn.getSchema());
            List<DbTableVO> tables = new ArrayList<>();
            try (ResultSet trs = md.getTables(catalog, blankToNull(schema), "%", new String[]{"TABLE"})) {
                while (trs.next()) {
                    String table = trs.getString("TABLE_NAME");
                    if (table == null || table.toLowerCase(Locale.ROOT).startsWith("information_schema")) {
                        continue;
                    }
                    List<DbColumnVO> cols = new ArrayList<>();
                    try (ResultSet crs = md.getColumns(catalog, blankToNull(schema), table, "%")) {
                        while (crs.next()) {
                            cols.add(new DbColumnVO(
                                    crs.getString("COLUMN_NAME"),
                                    formatJdbcType(crs.getString("TYPE_NAME"),
                                            crs.getInt("COLUMN_SIZE"), crs.getInt("DECIMAL_DIGITS")),
                                    crs.getString("REMARKS"),
                                    crs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                                    crs.getString("COLUMN_DEF"),
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
                                .map(c -> new DbColumnVO(c.name(), c.type(), c.comment(), c.nullable(),
                                        c.defaultValue(), "PRI", c.extra(), primaryKeys.contains(c.name())))
                                .toList();
                    }
                    long rowCount;
                    try {
                        Long count = jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM " + quote(table), Long.class);
                        rowCount = count == null ? 0 : count;
                    } catch (Exception e) {
                        rowCount = -1;
                    }
                    tables.add(new DbTableVO(database, table,
                            trs.getString("REMARKS") == null ? "" : trs.getString("REMARKS"),
                            rowCount, cols));
                }
            }
            tables.sort(Comparator.comparing(DbTableVO::name, String.CASE_INSENSITIVE_ORDER));
            return tables;
        } catch (SQLException e) {
            throw new IllegalStateException("读取数据库结构失败：" + e.getMessage(), e);
        }
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

    private Map<String, long[]> loadDatabaseCounts() {
        Map<String, long[]> counts = new HashMap<>();
        try {
            jdbcTemplate.query("""
                    SELECT TABLE_SCHEMA,
                           COUNT(DISTINCT TABLE_NAME) AS TABLE_COUNT,
                           COUNT(*) AS COLUMN_COUNT
                    FROM information_schema.COLUMNS
                    GROUP BY TABLE_SCHEMA
                    """, rs -> {
                String name = rs.getString("TABLE_SCHEMA");
                if (name != null && !SYSTEM_DATABASES.contains(name.toLowerCase(Locale.ROOT))) {
                    counts.put(name, new long[]{rs.getLong("TABLE_COUNT"), rs.getLong("COLUMN_COUNT")});
                }
            });
        } catch (Exception e) {
            log.debug("读取数据库统计失败：{}", e.getMessage());
        }
        return counts;
    }

    // ==================== 内部：权限 / 工具 ====================

    private String defaultDatabase() {
        try (Connection conn = dataSource.getConnection()) {
            String catalog = conn.getCatalog();
            if (catalog != null && !catalog.isBlank()) {
                return catalog;
            }
            String schema = conn.getSchema();
            return schema == null ? "" : schema;
        } catch (SQLException e) {
            throw new IllegalStateException("读取默认数据库失败：" + e.getMessage(), e);
        }
    }

    private String requireDatabase(String database) {
        String db = database == null || database.isBlank() ? defaultDatabase() : database.trim();
        if (db.isBlank()) {
            throw new BadRequestException("未指定数据库");
        }
        if (SYSTEM_DATABASES.contains(db.toLowerCase(Locale.ROOT))) {
            throw new BadRequestException("不允许维护系统数据库：" + db);
        }
        if (isMySql()) {
            try {
                Integer count = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = ?",
                        Integer.class, db);
                if (count == null || count == 0) {
                    throw new BadRequestException("数据库不存在或当前维护账号无权限：" + db);
                }
            } catch (BadRequestException e) {
                throw e;
            } catch (Exception e) {
                throw new BadRequestException("无法访问数据库：" + db + "（" + e.getMessage() + "）");
            }
        } else if (!db.equalsIgnoreCase(defaultDatabase())) {
            throw new BadRequestException("当前数据库不支持跨库访问：" + db);
        }
        return db;
    }

    private boolean ddlAllowed() {
        if (!isMySql()) {
            return true;
        }
        try {
            List<String> grants = jdbcTemplate.queryForList("SHOW GRANTS FOR CURRENT_USER()", String.class);
            return grants.stream()
                    .map(g -> g.toUpperCase(Locale.ROOT))
                    .anyMatch(g -> g.contains("ALL PRIVILEGES") || g.contains("ALTER"));
        } catch (Exception e) {
            log.debug("读取维护账号权限失败：{}", e.getMessage());
            return false;
        }
    }

    private boolean isMySql() {
        try (Connection conn = dataSource.getConnection()) {
            String product = conn.getMetaData().getDatabaseProductName();
            return product != null && product.toLowerCase(Locale.ROOT).contains("mysql");
        } catch (SQLException e) {
            return false;
        }
    }

    private String qualifiedTable(String database, String table) {
        return isMySql()
                ? quoteDatabase(database) + "." + quote(table)
                : quote(table);
    }

    /** 数据库名可能包含横线等字符，按数据库实际名称安全加引号。 */
    private String quoteDatabase(String database) {
        if (database == null || database.isBlank()) {
            throw new BadRequestException("数据库名不能为空");
        }
        return isMySql()
                ? "`" + database.replace("`", "``") + "`"
                : "\"" + database.replace("\"", "\"\"") + "\"";
    }

    private String quote(String identifier) {
        validateIdentifier(identifier, "标识符");
        return isMySql() ? "`" + identifier + "`" : "\"" + identifier + "\"";
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

    private boolean tableMatches(DbTableVO table, String keyword) {
        String lower = keyword.toLowerCase(Locale.ROOT);
        if (contains(table.name(), lower) || contains(table.comment(), lower)) {
            return true;
        }
        return table.columns().stream()
                .anyMatch(c -> contains(c.name(), lower)
                        || contains(c.comment(), lower)
                        || contains(c.type(), lower));
    }

    private boolean contains(String value, String lowerKeyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(lowerKeyword);
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private String sqlString(String value) {
        String escaped = value.replace("\0", "").replace("'", "''");
        if (isMySql()) {
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
        return message == null || message.isBlank() ? cur.getClass().getSimpleName() : truncate(message, 300);
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record DataSearchOutcome(List<DbSearchHitVO> hits, boolean truncated, int scannedTables) {
    }
}
