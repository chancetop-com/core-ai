package ai.core.server.tool;

import ai.core.tool.ToolCallResult;
import core.framework.http.HTTPResponse;

import java.util.List;

/**
 * Service API (api-app) tool surface owned by {@link ToolRegistryService}: lists the loaded
 * catalog for the API-Tool Hub and executes single operations (hub path keeps the upstream
 * status code and never fakes a 500 text on transport failure). Also holds the optional hub
 * catalog invalidator fired by {@code reloadApiTools()} so hub snapshots drop after Service
 * API definitions are rebuilt.
 *
 * @author stephen
 */
final class ServiceApiToolExecutor {
    private InternalApiToolLoader internalApiToolLoader;
    private Runnable catalogInvalidator;

    void setLoader(InternalApiToolLoader loader) {
        this.internalApiToolLoader = loader;
    }

    void setCatalogInvalidator(Runnable invalidator) {
        this.catalogInvalidator = invalidator;
    }

    InternalApiToolLoader loader() {
        return internalApiToolLoader;
    }

    List<InternalApiToolLoader.ApiAppInfo> listApps() {
        return internalApiToolLoader == null ? List.of() : internalApiToolLoader.listApiApps();
    }

    List<InternalApiToolLoader.ApiServiceInfo> listServices(String appName) {
        return internalApiToolLoader == null ? List.of() : internalApiToolLoader.listApiAppServices(appName);
    }

    /** One-pass app/service/operation snapshot for the API-Tool Hub catalog. */
    List<InternalApiToolLoader.ApiAppCatalog> loadCatalog() {
        return internalApiToolLoader == null ? List.of() : internalApiToolLoader.loadCatalog();
    }

    ToolCallResult callTool(String toolId, String argumentsJson) {
        requireLoader();
        if (!InternalApiToolLoader.isApiToolId(toolId)) {
            throw new IllegalArgumentException("unsupported service API tool id: " + toolId);
        }
        var tools = internalApiToolLoader.loadByToolId(toolId);
        if (tools.isEmpty()) {
            throw new RuntimeException("service API tool not found, id=" + toolId);
        }
        if (tools.size() != 1) {
            throw new IllegalArgumentException("test requires a single service API operation, id=" + toolId);
        }
        var payload = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
        return tools.getFirst().execute(payload);
    }

    HTTPResponse callOperation(String appName, String serviceName, String operationName, String argumentsJson) {
        requireLoader();
        return internalApiToolLoader.callOperationRaw(appName, serviceName, operationName, argumentsJson);
    }

    /** Hub catalog snapshots must reload after Service API definitions were rebuilt. */
    void invalidateCatalog() {
        if (catalogInvalidator != null) catalogInvalidator.run();
    }

    private void requireLoader() {
        if (internalApiToolLoader == null) throw new RuntimeException("service API tools are not initialized");
    }
}
