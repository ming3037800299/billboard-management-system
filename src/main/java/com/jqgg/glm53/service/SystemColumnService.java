package com.jqgg.glm53.service;

import com.jqgg.glm53.dto.DbColumnVO;
import com.jqgg.glm53.dto.DbTableVO;
import com.jqgg.glm53.dto.SystemColumnForm;
import com.jqgg.glm53.exception.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 系统字段（billboard 核心列）结构维护。
 *
 * 只在兼容 JPA 字段类型的范围内允许调整类型 / 长度 / 精度 / 可空性：
 *   - String 字段：VARCHAR / TEXT；
 *   - BigDecimal 字段：DECIMAL；
 *   - LocalDateTime 字段：DATETIME；
 *   - boolean 字段：TINYINT(1)；
 *   - Long 主键：BIGINT（只读）。
 *
 * 物理列名不可修改，默认值、ON UPDATE、索引等由 MODIFY COLUMN 保留。
 */
@Service
public class SystemColumnService {

    private static final Logger log = LoggerFactory.getLogger(SystemColumnService.class);

    private static final Map<String, Set<String>> ALLOWED_TYPES = Map.ofEntries(
            Map.entry("id", Set.of("BIGINT")),
            Map.entry("name", Set.of("VARCHAR", "TEXT")),
            Map.entry("code", Set.of("VARCHAR")),
            Map.entry("road", Set.of("VARCHAR")),
            Map.entry("section", Set.of("VARCHAR", "TEXT")),
            Map.entry("address", Set.of("VARCHAR", "TEXT")),
            Map.entry("longitude", Set.of("DECIMAL")),
            Map.entry("latitude", Set.of("DECIMAL")),
            Map.entry("images", Set.of("TEXT", "VARCHAR")),
            Map.entry("price", Set.of("DECIMAL")),
            Map.entry("price_unit", Set.of("VARCHAR")),
            Map.entry("price_note", Set.of("VARCHAR", "TEXT")),
            Map.entry("size", Set.of("VARCHAR", "TEXT")),
            Map.entry("material", Set.of("VARCHAR", "TEXT")),
            Map.entry("visible_area", Set.of("VARCHAR", "TEXT")),
            Map.entry("audience", Set.of("VARCHAR", "TEXT")),
            Map.entry("status", Set.of("VARCHAR")),
            Map.entry("description", Set.of("TEXT", "VARCHAR")),
            Map.entry("deleted", Set.of("TINYINT")),
            Map.entry("created_at", Set.of("DATETIME")),
            Map.entry("updated_at", Set.of("DATETIME")));

    /** 结构由业务代码维护的字段：只允许改注释。 */
    private static final Set<String> READONLY_STRUCTURE = Set.of(
            "id", "deleted", "created_at", "updated_at");

    /** 可空性由业务代码维护的字段：不允许修改。 */
    private static final Set<String> FIXED_NULLABLE = Set.of(
            "id", "code", "deleted", "created_at", "updated_at");

    private final DatabaseMaintenanceService databaseService;
    private final JdbcTemplate jdbcTemplate;

    public SystemColumnService(@Qualifier("dataSource") DataSource dataSource,
                               DatabaseMaintenanceService databaseService) {
        this.databaseService = databaseService;
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.jdbcTemplate.setQueryTimeout(5);
    }

    /** 修改系统字段结构 / 注释。 */
    public void alter(String column, SystemColumnForm form) {
        DbColumnVO current = currentColumn(column);
        String key = current.name().toLowerCase(Locale.ROOT);
        Set<String> allowed = ALLOWED_TYPES.get(key);
        if (allowed == null) {
            throw new BadRequestException("字段 " + column + " 不是可编辑的系统字段");
        }
        String requested = form.getType() == null ? "" : form.getType().trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(requested)) {
            throw new BadRequestException("字段 " + column + " 不支持修改为 " + requested
                    + "，可选类型：" + String.join(" / ", allowed));
        }

        boolean nullable = form.getNullable() != null ? form.getNullable() : current.nullable();
        String comment = form.getComment() == null ? "" : form.getComment().trim();

        if (READONLY_STRUCTURE.contains(key)) {
            boolean changed = !requested.equals(baseType(current.type()))
                    || (form.getLength() != null && !Objects.equals(form.getLength(), currentLength(current.type())))
                    || (form.getDecimals() != null && !Objects.equals(form.getDecimals(), currentScale(current.type())))
                    || nullable != current.nullable();
            if (changed) {
                throw new BadRequestException("字段 " + column + " 的结构由业务代码维护，只能修改注释");
            }
            databaseService.updateColumnComment("billboard", current.name(), comment);
            return;
        }

        if (FIXED_NULLABLE.contains(key) && nullable != current.nullable()) {
            throw new BadRequestException("字段 " + column + " 的可空性由业务代码维护，不能修改");
        }

        String columnType = buildColumnType(requested, form, current);
        boolean structural = !columnType.equalsIgnoreCase(current.type())
                || nullable != current.nullable();
        if (!structural) {
            databaseService.updateColumnComment("billboard", current.name(), comment);
            return;
        }
        if (!databaseService.isMySql()) {
            throw new BadRequestException("当前数据库暂不支持在线修改系统字段结构，请使用 MySQL 8");
        }

        String defaultSql = defaultSql(current, requested, nullable);
        if (!nullable && current.nullable()) {
            databaseService.fillNulls("billboard", current.name(),
                    defaultSql != null ? defaultSql : implicitDefault(requested));
        }
        String onUpdate = current.extra() != null
                && current.extra().toLowerCase(Locale.ROOT).contains("on update")
                ? " ON UPDATE CURRENT_TIMESTAMP" : "";
        StringBuilder ddl = new StringBuilder()
                .append("ALTER TABLE ").append(databaseService.quote("billboard"))
                .append(" MODIFY COLUMN ").append(databaseService.quote(current.name()))
                .append(' ').append(columnType)
                .append(nullable ? " NULL" : " NOT NULL");
        if (defaultSql != null) {
            ddl.append(" DEFAULT ").append(defaultSql);
        }
        ddl.append(onUpdate);
        ddl.append(" COMMENT ").append(sqlString(comment));
        try {
            jdbcTemplate.execute(ddl.toString());
            databaseService.invalidateSchemaCache();
        } catch (Exception e) {
            log.warn("修改系统字段失败，SQL={}，原因={}", ddl, e.getMessage());
            throw new BadRequestException("数据库修改字段失败：" + rootMessage(e)
                    + "。请确认类型转换兼容，且现有数据长度不超过新限制。");
        }
    }

    private DbColumnVO currentColumn(String column) {
        DbTableVO table = databaseService.tables("billboard").stream()
                .filter(t -> t.name().equalsIgnoreCase("billboard"))
                .findFirst()
                .orElseThrow(() -> new BadRequestException("billboard 表不存在"));
        return table.columns().stream()
                .filter(c -> c.name().equalsIgnoreCase(column))
                .findFirst()
                .orElseThrow(() -> new BadRequestException("字段不存在：" + column));
    }

    private String buildColumnType(String requested, SystemColumnForm form, DbColumnVO current) {
        return switch (requested) {
            case "VARCHAR" -> {
                Integer length = form.getLength() != null ? form.getLength() : currentLength(current.type());
                if (length == null) {
                    length = 200;
                }
                if (length < 1 || length > 2000) {
                    throw new BadRequestException("VARCHAR 长度需在 1 - 2000 之间");
                }
                yield "VARCHAR(" + length + ")";
            }
            case "TEXT" -> "TEXT";
            case "DECIMAL" -> {
                Integer precision = form.getLength() != null ? form.getLength() : currentLength(current.type());
                if (precision == null) {
                    precision = 18;
                }
                Integer scale = form.getDecimals() != null ? form.getDecimals() : currentScale(current.type());
                if (scale == null) {
                    scale = 2;
                }
                if (precision < 1 || precision > 65) {
                    throw new BadRequestException("DECIMAL 精度需在 1 - 65 之间");
                }
                if (scale < 0 || scale > 30 || scale > precision) {
                    throw new BadRequestException("DECIMAL 小数位需在 0 - 30 之间且不能大于精度");
                }
                yield "DECIMAL(" + precision + "," + scale + ")";
            }
            case "DATETIME" -> "DATETIME";
            case "TINYINT" -> "TINYINT(1)";
            case "BIGINT" -> "BIGINT";
            default -> throw new BadRequestException("不支持的字段类型：" + requested);
        };
    }

    private String defaultSql(DbColumnVO current, String requestedType, boolean nullable) {
        String value = current.defaultValue();
        if (value != null && !value.isBlank()) {
            if ("CURRENT_TIMESTAMP".equalsIgnoreCase(value)) {
                return "CURRENT_TIMESTAMP";
            }
            if (isNumeric(requestedType)) {
                return value;
            }
            return sqlString(value);
        }
        if (!nullable && current.nullable()) {
            return implicitDefault(requestedType);
        }
        return null;
    }

    private String implicitDefault(String type) {
        return switch (type) {
            case "TEXT" -> "('')";
            case "DATETIME" -> sqlString("1970-01-01 00:00:00");
            case "VARCHAR" -> sqlString("");
            default -> "0";
        };
    }

    private boolean isNumeric(String type) {
        return "DECIMAL".equals(type) || "TINYINT".equals(type) || "BIGINT".equals(type);
    }

    private String baseType(String dbType) {
        String value = dbType == null ? "" : dbType.trim().toUpperCase(Locale.ROOT);
        int idx = value.indexOf('(');
        return idx >= 0 ? value.substring(0, idx) : value;
    }

    private Integer currentLength(String dbType) {
        if (dbType == null) {
            return null;
        }
        var matcher = java.util.regex.Pattern
                .compile("^\\s*[A-Za-z]+\\s*\\((\\d+)(?:\\s*,\\s*(\\d+))?\\)")
                .matcher(dbType);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    private Integer currentScale(String dbType) {
        if (dbType == null) {
            return null;
        }
        var matcher = java.util.regex.Pattern
                .compile("^\\s*[A-Za-z]+\\s*\\((\\d+)\\s*,\\s*(\\d+)\\)")
                .matcher(dbType);
        return matcher.find() ? Integer.parseInt(matcher.group(2)) : null;
    }

    private String sqlString(String value) {
        String escaped = value.replace("\0", "").replace("'", "''");
        if (databaseService.isMySql()) {
            escaped = escaped.replace("\\", "\\\\");
        }
        return "'" + escaped + "'";
    }

    private String rootMessage(Throwable e) {
        Throwable current = e;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
