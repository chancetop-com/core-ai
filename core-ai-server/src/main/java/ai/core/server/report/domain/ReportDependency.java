package ai.core.server.report.domain;

import ai.core.server.domain.Dataset;
import ai.core.server.domain.SchemaField;
import ai.core.server.domain.SchemaFieldType;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * The schema fields a report depends on. Fingerprints change only when a referenced field disappears or changes type.
 *
 * @author core-ai
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ReportDependency {
    public static ReportDependency of(Dataset dataset, Set<String> fieldNames) {
        var dependency = new ReportDependency();
        dependency.datasetId = dataset.id;
        dependency.schemaVersion = null;
        dependency.fields = new ArrayList<>();
        if (dataset.schema != null && fieldNames != null) {
            for (var schemaField : dataset.schema) {
                if (schemaField == null || schemaField.name == null || !fieldNames.contains(schemaField.name)) continue;
                var field = new Field();
                field.name = schemaField.name;
                field.type = typeName(schemaField);
                field.fingerprint = fingerprint(dataset, schemaField);
                dependency.fields.add(field);
            }
        }
        dependency.fields.sort(Comparator.comparing(field -> field.name));
        return dependency;
    }

    public static String fingerprint(Dataset dataset, SchemaField field) {
        return sha256(dataset.id + '\u0000' + field.name + '\u0000' + typeName(field));
    }

    private static String typeName(SchemaField field) {
        SchemaFieldType type = field.type != null ? field.type : SchemaFieldType.STRING;
        return type.name();
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public String datasetId;
    public Integer schemaVersion;
    public List<Field> fields;

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class Field {
        public String name;
        public String type;
        public String fingerprint;
    }
}
