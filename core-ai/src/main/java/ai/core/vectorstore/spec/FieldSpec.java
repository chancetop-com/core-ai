package ai.core.vectorstore.spec;

/**
 * Scalar field definition of a {@link CollectionSpec}.
 *
 * @author stephen
 */
public record FieldSpec(String name, ScalarType type, Integer maxLength, boolean primaryKey, boolean autoId) {
    public static FieldSpec varchar(String name, int maxLength) {
        return new FieldSpec(name, ScalarType.VARCHAR, maxLength, false, false);
    }

    public static FieldSpec int64(String name) {
        return new FieldSpec(name, ScalarType.INT64, null, false, false);
    }

    public static FieldSpec bool(String name) {
        return new FieldSpec(name, ScalarType.BOOL, null, false, false);
    }

    public static FieldSpec json(String name) {
        return new FieldSpec(name, ScalarType.JSON, null, false, false);
    }

    public static FieldSpec autoIdInt64(String name) {
        return new FieldSpec(name, ScalarType.INT64, null, true, true);
    }

    public static FieldSpec varcharPk(String name, int maxLength) {
        return new FieldSpec(name, ScalarType.VARCHAR, maxLength, true, false);
    }
}
