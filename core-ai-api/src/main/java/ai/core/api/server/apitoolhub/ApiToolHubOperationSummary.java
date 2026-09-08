package ai.core.api.server.apitoolhub;

import core.framework.api.json.Property;

/**
 * Search-result view of one Service API operation, without schemas (fetch the detail
 * endpoint for {@code input_schema}/{@code output_schema}/{@code example}).
 *
 * @author stephen
 */
public class ApiToolHubOperationSummary {
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

    @Property(name = "score")
    public Integer score;
}
