package ai.core.api.server.apitoolhub;

import core.framework.api.json.Property;

/**
 * Resolves an existing function name ({@code app_service_operation}) back to the
 * canonical three-part name. 404 when the tool name matches nothing in the catalog.
 *
 * @author stephen
 */
public class ApiToolHubLookupResponse {
    @Property(name = "operation")
    public ApiToolHubOperationSummary operation;
}
