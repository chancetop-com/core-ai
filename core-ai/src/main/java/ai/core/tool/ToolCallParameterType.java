package ai.core.tool;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public enum ToolCallParameterType {
    @Property(name = "String")
    String,
    @Property(name = "Boolean")
    Boolean,
    @Property(name = "Double")
    Double,
    @Property(name = "LocalDate")
    LocalDate,
    @Property(name = "ZonedDateTime")
    ZonedDateTime,
    @Property(name = "Integer")
    Integer,
    @Property(name = "LocalTime")
    LocalTime,
    @Property(name = "LocalDateTime")
    LocalDateTime,
    @Property(name = "Long")
    Long,
    @Property(name = "BigDecimal")
    BigDecimal,
}
