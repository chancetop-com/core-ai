package ai.core.server.schedule;

import ai.core.mcp.server.McpServerService;
import ai.core.server.apimcp.serviceapi.service.ServiceApiService;
import ai.core.server.tool.ToolRegistryService;
import core.framework.inject.Inject;
import core.framework.scheduler.Job;
import core.framework.scheduler.JobContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author stephen
 */
public class ToolRegistrySyncJob implements Job {
    private static final Logger LOGGER = LoggerFactory.getLogger(ToolRegistrySyncJob.class);

    @Inject
    ToolRegistryService toolRegistryService;

    @Inject
    ServiceApiService serviceApiService;

    @Inject
    McpServerService mcpServerService;

    private volatile String lastApiSnapshot;

    @Override
    public void execute(JobContext context) {
        toolRegistryService.syncDatabaseTools();
        reloadApiToolsIfChanged();
    }

    // service api tools are loaded once at startup; reload them automatically (both the MCP server
    // endpoint used by external MCP clients and the internal tool registry) when service_api records change,
    // so admin updates take effect on every pod without restart or manual reload
    private void reloadApiToolsIfChanged() {
        try {
            var snapshot = serviceApiService.configSnapshot();
            if (snapshot.equals(lastApiSnapshot)) return;
            mcpServerService.reload();
            toolRegistryService.reloadApiTools();
            lastApiSnapshot = snapshot;
            LOGGER.info("service api tools reloaded, snapshot={}", snapshot);
        } catch (Exception e) {
            LOGGER.error("failed to reload service api tools", e);
        }
    }
}
