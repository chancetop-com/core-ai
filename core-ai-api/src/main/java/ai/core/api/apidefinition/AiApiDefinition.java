package ai.core.api.apidefinition;

import core.framework.api.json.Property;

import java.util.ArrayList;
import java.util.List;

/**
 * @author stephen
 */
public class AiApiDefinition {
    public static final String SCHEMA_V1 = "core-ai/api-definition/1";

    @Property(name = "schema")
    public String schema;

    @Property(name = "description")
    public String description;

    @Property(name = "app")
    public String app;

    @Property(name = "version")
    public String version;

    @Property(name = "services")
    public List<Service> services = new ArrayList<>();

    @Property(name = "types")
    public List<Type> types = new ArrayList<>();

    public static class Service {
        @Property(name = "name")
        public String name;

        @Property(name = "description")
        public String description;

        @Property(name = "operations")
        public List<Operation> operations = new ArrayList<>();
    }

    public static class Operation {
        @Property(name = "name")
        public String name;

        @Property(name = "description")
        public String description;

        @Property(name = "example")
        public String example;

        @Property(name = "method")
        public String method;

        @Property(name = "path")
        public String path;

        @Property(name = "pathParams")
        public List<PathParam> pathParams = new ArrayList<>();

        @Property(name = "requestType")
        public String requestType;

        @Property(name = "responseType")
        public String responseType;

        @Property(name = "optional")
        public Boolean optional;

        @Property(name = "deprecated")
        public Boolean deprecated;
    }

    public static class PathParam {
        @Property(name = "name")
        public String name;

        @Property(name = "description")
        public String description;

        @Property(name = "example")
        public String example;

        @Property(name = "type")
        public String type;
    }

    public static class Type {
        @Property(name = "name")
        public String name;

        @Property(name = "type")
        public String type;

        @Property(name = "fields")
        public List<Field> fields = new ArrayList<>();

        @Property(name = "enumConstants")
        public List<EnumConstant> enumConstants = new ArrayList<>();
    }

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
