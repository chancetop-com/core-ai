package ai.core.api.server.tool;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListApiAppServicesResponse {
    @Property(name = "services")
    public List<ApiServiceView> services;

    public static class ApiServiceView {
        @Property(name = "name")
        public String name;

        @Property(name = "description")
        public String description;

        @Property(name = "operation_count")
        public Integer operationCount;

        @Property(name = "operations")
        public List<ApiOperationView> operations;
    }

    public static class ApiOperationView {
        @Property(name = "name")
        public String name;

        @Property(name = "tool_name")
        public String toolName;

        @Property(name = "description")
        public String description;

        @Property(name = "method")
        public String method;

        @Property(name = "path")
        public String path;

        @Property(name = "request_type")
        public String requestType;

        @Property(name = "response_type")
        public String responseType;

        @Property(name = "input_schema")
        public String inputSchema;

        @Property(name = "output_schema")
        public String outputSchema;
    }
}
