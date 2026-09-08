package ai.core.api.server.apitoolhub;

import core.framework.api.json.Property;

/**
 * One visible Service API app in the API-Tool Hub. {@code base_url} is not a secret —
 * agents already see it inside operation schemas/descriptions.
 *
 * @author stephen
 */
public class ApiToolHubAppView {
    @Property(name = "name")
    public String name;

    @Property(name = "description")
    public String description;

    @Property(name = "base_url")
    public String baseUrl;

    @Property(name = "version")
    public String version;

    @Property(name = "service_count")
    public Integer serviceCount;

    @Property(name = "operation_count")
    public Integer operationCount;
}
