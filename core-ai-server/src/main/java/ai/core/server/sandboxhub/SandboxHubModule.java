package ai.core.server.sandboxhub;

import ai.core.api.server.SandboxHubWebService;
import core.framework.module.Module;

/**
 * Sandbox Hub: the capabilities of the session that owns a sandbox, addressed from inside that
 * sandbox through the runtime's loopback proxy. Loaded after the modules that provide the session
 * manager ({@code SessionModule}) and the hub call audit ({@code McpHubModule}).
 *
 * @author xander
 */
public class SandboxHubModule extends Module {
    @Override
    protected void initialize() {
        bind(SandboxHubService.class);
        api().service(SandboxHubWebService.class, bind(SandboxHubWebServiceImpl.class));
    }
}
