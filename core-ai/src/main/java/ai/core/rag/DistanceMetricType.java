package ai.core.rag;

import core.framework.api.json.Property;

import java.util.Locale;

/**
 * @author stephen
 */
public enum DistanceMetricType {
    @Property(name = "COSINE")
    COSINE,
    @Property(name = "EUCLIDEAN")
    EUCLIDEAN,
    @Property(name = "MANHATTAN")
    MANHATTAN,
    @Property(name = "PRODUCT")
    PRODUCT,
    @Property(name = "CANBERRA")
    CANBERRA,
    @Property(name = "BRAY_CURTIS")
    BRAY_CURTIS,
    @Property(name = "CORRELATION")
    CORRELATION;

    public DistanceMetricType of(String value) {
        return DistanceMetricType.valueOf(value.toUpperCase(Locale.ROOT));
    }
}
