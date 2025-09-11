package ai.core.api.apidefinition;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.List;

/**
 * @author stephen
 */
public class ApiDefinitionType {
    @Property(name = "name")
    public String name;

    @Property(name = "type")
    public String type;

    @Property(name = "fields")
    public List<Field> fields;

    @Property(name = "enumConstants")
    public List<EnumConstant> enumConstants;

    public static class Field {
        @Property(name = "name")
        public String name;

        @Property(name = "description")
        public String description;

        @Property(name = "example")
        public String example;

        @Property(name = "type")
        public String type;

        @Property(name = "typeParams")
        public List<String> typeParams;

        @NotNull
        @Property(name = "constraints")
        public Constraints constraints = new Constraints();
    }

    public static class Constraints {
        @Property(name = "notNull")
        public Boolean notNull;

        @Property(name = "notBlank")
        public Boolean notBlank;

        @Property(name = "min")
        public Double min;

        @Property(name = "max")
        public Double max;

        @Property(name = "size")
        public Size size;

        @Property(name = "pattern")
        public String pattern;
    }

    public static class Size {
        @Property(name = "min")
        public Integer min;

        @Property(name = "max")
        public Integer max;
    }

    public static class EnumConstant {
        @Property(name = "name")
        public String name;

        @Property(name = "value")
        public String value;
    }
}
