package ai.core.api.server.apitoolhub;

import core.framework.api.json.Property;

/**
 * Full detail of one Service API operation: the summary fields plus the JSON schemas
 * of its request/response (text form — dynamic nested objects do not fit view beans).
 *
 * @author stephen
 */
public class ApiToolHubOperationDetail {
    @Property(name = "qualified_name")
    public String qualifiedName;

    @Property(name = "tool_name")
    public String toolName;

    @Property(name = "ref_id")
    public String refId;

    @Property(name = "app")
    public String app;

    @Property(name = "service")
    public String service;

    @Property(name = "name")
    public String name;

    @Property(name = "method")
    public String method;

    @Property(name = "path")
    public String path;

    @Property(name = "description")
    public String description;

    @Property(name = "need_auth")
    public Boolean needAuth;

    @Property(name = "deprecated")
    public Boolean deprecated;

    @Property(name = "example")
    public String example;

    @Property(name = "request_type")
    public String requestType;

    @Property(name = "response_type")
    public String responseType;

    @Property(name = "input_schema")
    public String inputSchema;

    @Property(name = "output_schema")
    public String outputSchema;
}
