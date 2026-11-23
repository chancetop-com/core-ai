package ai.core.server.dataset;

import ai.core.server.domain.Dataset;
import ai.core.server.domain.SchemaField;
import ai.core.server.domain.SchemaFieldType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rules shared by the dataset record write paths: the agent tools and the hub dataset service must
 * store exactly the same fields and types, otherwise the two access surfaces drift apart.
 *
 * @author stephen
 */
public final class DatasetRecordWriteRules {
    /**
     * Keeps only the fields declared in the dataset schema and converts their values to the declared type.
     * Null values are dropped, matching the stored shape of a new record.
     */
    public static Map<String, Object> normalize(Dataset dataset, Map<String, Object> data) {
        return normalizeWithDroppedFields(dataset, data).data();
    }

    /**
     * Patch semantics: null still means delete the field, so it is kept for the merge-patch path; every other
     * value is filtered and type-checked like an insert.
     */
    public static Map<String, Object> normalizePatch(Dataset dataset, Map<String, Object> data) {
        return normalizePatchWithDroppedFields(dataset, data).data();
    }

    public static NormalizedWrite normalizeWithDroppedFields(Dataset dataset, Map<String, Object> data) {
        return normalizeInternal(dataset, data, false);
    }

    public static NormalizedWrite normalizePatchWithDroppedFields(Dataset dataset, Map<String, Object> data) {
        return normalizeInternal(dataset, data, true);
    }

    /**
     * The failure a write gets when every supplied field was outside the schema, naming the fields that were
     * dropped so a typo in a column name is visible instead of silently writing a partial row.
     */
    public static String noMatchingFieldsMessage(Dataset dataset, List<String> droppedFields) {
        var message = "none of the provided fields match the dataset schema: " + schemaFieldNames(dataset);
        if (droppedFields != null && !droppedFields.isEmpty()) {
            message += "; unknown fields: " + droppedFields;
        }
        return message;
    }

    private static NormalizedWrite normalizeInternal(Dataset dataset, Map<String, Object> data, boolean patch) {
        if (data == null) return new NormalizedWrite(new LinkedHashMap<>(), List.of());
        if (dataset == null || dataset.schema == null || dataset.schema.isEmpty()) {
            return new NormalizedWrite(new LinkedHashMap<>(data), List.of());
        }
        var normalized = new LinkedHashMap<String, Object>();
        var known = new HashSet<String>();
        for (var field : dataset.schema) {
            if (field == null || field.name == null || field.name.isBlank()) continue;
            known.add(field.name);
            if (!data.containsKey(field.name)) continue;
            var value = data.get(field.name);
            if (value == null) {
                if (patch) normalized.put(field.name, null);
            } else {
                normalized.put(field.name, convert(field, value));
            }
        }
        var dropped = new ArrayList<String>();
        for (var key : data.keySet()) {
            if (!known.contains(key)) dropped.add(key);
        }
        return new NormalizedWrite(normalized, List.copyOf(dropped));
    }

    public static List<String> schemaFieldNames(Dataset dataset) {
        return dataset != null && dataset.schema != null
                ? dataset.schema.stream().map(f -> f.name).toList() : List.of();
    }

    private static Object convert(SchemaField field, Object value) {
        var type = field.type != null ? field.type : SchemaFieldType.STRING;
        return switch (type) {
            case NUMBER -> number(field, value);
            case STRING -> string(field, value);
            case BOOLEAN -> bool(field, value);
            case DATE -> date(field, value);
            case DATETIME -> datetime(field, value);
        };
    }

    private static Object number(SchemaField field, Object value) {
        if (value instanceof Number) return value;
        if (value instanceof String text) {
            try {
                return new BigDecimal(text.trim());
            } catch (NumberFormatException e) {
                throw invalid(field, value, e);
            }
        }
        throw invalid(field, value);
    }

    private static Object string(SchemaField field, Object value) {
        if (value instanceof String) return value;
        if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
        throw invalid(field, value);
    }

    private static Object bool(SchemaField field, Object value) {
        if (value instanceof Boolean) return value;
        if (value instanceof String text) {
            var normalized = text.trim();
            if ("true".equalsIgnoreCase(normalized)) return Boolean.TRUE;
            if ("false".equalsIgnoreCase(normalized)) return Boolean.FALSE;
        }
        throw invalid(field, value);
    }

    private static Object date(SchemaField field, Object value) {
        if (value instanceof LocalDate localDate) return localDate.toString();
        if (value instanceof String text) {
            var normalized = text.trim();
            try {
                return LocalDate.parse(normalized.length() >= 10 ? normalized.substring(0, 10) : normalized).toString();
            } catch (DateTimeParseException e) {
                throw invalid(field, value, e);
            }
        }
        throw invalid(field, value);
    }

    private static Object datetime(SchemaField field, Object value) {
        if (value instanceof OffsetDateTime offsetDateTime) return offsetDateTime.toString();
        if (value instanceof ZonedDateTime zonedDateTime) return zonedDateTime.toOffsetDateTime().toString();
        if (value instanceof Instant instant) return instant.toString();
        if (value instanceof LocalDateTime localDateTime) return localDateTime.atOffset(ZoneOffset.UTC).toString();
        if (value instanceof String text) {
            var normalized = text.trim();
            try {
                var parsed = DateTimeFormatter.ISO_DATE_TIME.parseBest(normalized, OffsetDateTime::from, LocalDateTime::from);
                if (parsed instanceof OffsetDateTime offsetDateTime) return offsetDateTime.toString();
                return ((LocalDateTime) parsed).atOffset(ZoneOffset.UTC).toString();
            } catch (DateTimeParseException e) {
                throw invalid(field, value, e);
            }
        }
        throw invalid(field, value);
    }

    private static IllegalArgumentException invalid(SchemaField field, Object value) {
        return invalid(field, value, null);
    }

    private static IllegalArgumentException invalid(SchemaField field, Object value, Throwable cause) {
        var type = field.type != null ? field.type : SchemaFieldType.STRING;
        var message = "field '" + field.name + "' expects " + type + " but got " + value.getClass().getSimpleName();
        return cause != null ? new IllegalArgumentException(message, cause) : new IllegalArgumentException(message);
    }

    private DatasetRecordWriteRules() {
    }

    /**
     * The write-side view of a payload: the fields that will be stored plus the keys the dataset schema does not
     * know. Kept together so a caller can report a silently dropped column instead of guessing why a value is
     * missing.
     */
    public record NormalizedWrite(Map<String, Object> data, List<String> droppedFields) {
    }
}
