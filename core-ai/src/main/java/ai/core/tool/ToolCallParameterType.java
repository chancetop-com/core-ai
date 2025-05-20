package ai.core.tool;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public enum ToolCallParameterType {
    @Property(name = "String")
    STRING,
    @Property(name = "Boolean")
    BOOLEAN,
    @Property(name = "Double")
    DOUBLE,
    @Property(name = "LocalDate")
    LOCALDATE,
    @Property(name = "ZonedDateTime")
    ZONEDDATETIME,
    @Property(name = "Integer")
    INTEGER,
    @Property(name = "LocalTime")
    LOCALTIME,
    @Property(name = "LocalDateTime")
    LOCALDATETIME,
    @Property(name = "Long")
    LONG,
    @Property(name = "BigDecimal")
    BIGDECIMAL;

    public Class<?> getType() {
        return switch (this) {
            case STRING -> String.class;
            case BOOLEAN -> Boolean.class;
            case DOUBLE -> Double.class;
            case LOCALDATE -> java.time.LocalDate.class;
            case ZONEDDATETIME -> java.time.ZonedDateTime.class;
            case INTEGER -> Integer.class;
            case LOCALTIME -> java.time.LocalTime.class;
            case LOCALDATETIME -> java.time.LocalDateTime.class;
            case LONG -> Long.class;
            case BIGDECIMAL -> java.math.BigDecimal.class;
        };
    }
}
