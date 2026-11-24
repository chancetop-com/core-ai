package ai.core.server.report.application;

import ai.core.server.domain.SchemaFieldType;
import ai.core.server.report.domain.ReportConstants;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Field-name and scalar-value rules shared by report validation and query compilation.
 *
 * @author core-ai
 */
final class ReportSpecFields {
    static String dataField(String field) {
        if (field == null || !field.startsWith(ReportConstants.DATA_FIELD_PREFIX)) return null;
        var name = field.substring(ReportConstants.DATA_FIELD_PREFIX.length());
        if (name.isBlank() || name.contains(".")) return null;
        return name;
    }

    static String recordField(String field) {
        if (field == null) return null;
        var name = field.trim();
        if (name.startsWith(ReportConstants.DATA_FIELD_PREFIX)) name = name.substring(ReportConstants.DATA_FIELD_PREFIX.length());
        if (name.isBlank() || name.contains(".")) return null;
        return name;
    }

    static boolean matchesType(SchemaFieldType type, Object value) {
        return switch (type != null ? type : SchemaFieldType.STRING) {
            case NUMBER -> value instanceof Number;
            case BOOLEAN -> value instanceof Boolean;
            case STRING -> value instanceof String;
            case DATE -> value instanceof String text && localDate(text) != null;
            case DATETIME -> value instanceof String text && instant(text) != null;
        };
    }

    static Object normalizeValue(SchemaFieldType type, Object value) {
        return switch (type != null ? type : SchemaFieldType.STRING) {
            case NUMBER -> new BigDecimal(value.toString());
            case DATE -> localDate((String) value);
            case DATETIME -> instant((String) value);
            default -> value;
        };
    }

    static String localDate(String text) {
        try {
            var normalized = text.trim();
            return LocalDate.parse(normalized.length() >= 10 ? normalized.substring(0, 10) : normalized).toString();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static String instant(String text) {
        try {
            var parsed = DateTimeFormatter.ISO_DATE_TIME.parseBest(text.trim(), OffsetDateTime::from, LocalDateTime::from);
            if (parsed instanceof OffsetDateTime offsetDateTime) return offsetDateTime.toInstant().toString();
            return ((LocalDateTime) parsed).atOffset(ZoneOffset.UTC).toInstant().toString();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static String typeName(SchemaFieldType type) {
        return type != null ? type.name() : SchemaFieldType.STRING.name();
    }

    static boolean isScalar(Object value) {
        return value == null || value instanceof String || value instanceof Number || value instanceof Boolean;
    }

    private ReportSpecFields() {
    }
}
