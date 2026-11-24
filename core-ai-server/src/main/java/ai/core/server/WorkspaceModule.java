package ai.core.server;

import ai.core.api.server.workspace.WorkspaceWebService;
import ai.core.server.sandbox.SandboxService;
import ai.core.server.sandbox.WorkspaceSandboxFactory;
import ai.core.server.sandbox.agentsandbox.AgentSandboxApiVersion;
import ai.core.server.sandbox.agentsandbox.AgentSandboxClient;
import ai.core.server.sandbox.agentsandbox.AgentSandboxProvider;
import ai.core.server.sandbox.agentsandbox.SandboxPodPlacement;
import ai.core.server.sandbox.kubernetes.KubernetesClient;
import ai.core.server.session.ChatSessionStore;
import ai.core.server.workspace.KubernetesWorkspaceProvisioner;
import ai.core.server.workspace.PgWorkspaceBindingStore;
import ai.core.server.workspace.PgWorkspaceEventStore;
import ai.core.server.workspace.PgWorkspaceStore;
import ai.core.server.workspace.PgWorkspaceVolumeStore;
import ai.core.server.workspace.WorkspaceConfig;
import ai.core.server.workspace.WorkspaceDefaults;
import ai.core.server.workspace.WorkspaceRuntimeManager;
import ai.core.server.workspace.WorkspaceService;
import ai.core.server.workspace.WorkspaceSessionBinder;
import ai.core.server.workspace.WorkspaceStores;
import ai.core.server.web.WorkspaceWebServiceImpl;
import core.framework.module.Module;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;

/**
 * Wires the workspace stores and service. The module must load after {@link SandboxModule}: the
 * provisioner and runtime manager reuse the agent-sandbox provider's clients instead of resolving
 * namespace and token a second time.
 *
 * @author stephen
 */
public class WorkspaceModule extends Module {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkspaceModule.class);

    @Override
    protected void initialize() {
        var config = config();
        // The PG stores inject Database eagerly; a deployment that never configured PostgreSQL
        // (local mongo mode) must still start with the feature flag off.
        WorkspaceStores stores;
        if (config.enabled) {
            stores = new WorkspaceStores(bind(new PgWorkspaceStore()), bind(new PgWorkspaceVolumeStore()),
                    bind(new PgWorkspaceBindingStore()), bind(new PgWorkspaceEventStore()));
        } else {
            stores = new WorkspaceStores(null, null, null, null);
        }

        KubernetesClient kubernetesClient = null;
        AgentSandboxClient agentSandboxClient = null;
        var placement = SandboxPodPlacement.NONE;
        var apiVersion = AgentSandboxApiVersion.V1_ALPHA1;
        var provider = bean(SandboxService.class).provider();
        if (provider instanceof AgentSandboxProvider agentSandbox) {
            kubernetesClient = agentSandbox.kubernetesClient();
            agentSandboxClient = agentSandbox.client();
            placement = agentSandbox.podPlacement();
            apiVersion = agentSandbox.apiVersion();
        }

        var provisioner = new KubernetesWorkspaceProvisioner(kubernetesClient);
        var runtimes = new WorkspaceRuntimeManager(agentSandboxClient, placement, apiVersion);
        var wakeExecutor = Executors.newFixedThreadPool(2, runnable -> {
            var thread = new Thread(runnable, "workspace-wake");
            thread.setDaemon(true);
            return thread;
        });
        bind(new WorkspaceService(stores, provisioner, runtimes, config, wakeExecutor));
        bind(new WorkspaceSessionBinder(bean(WorkspaceService.class)));
        var sandboxService = bean(SandboxService.class);
        bind(new WorkspaceSandboxFactory(bean(WorkspaceService.class), bean(ChatSessionStore.class), provider));
        sandboxService.workspaceSandboxFactory(bean(WorkspaceSandboxFactory.class));
        api().service(WorkspaceWebService.class, bind(WorkspaceWebServiceImpl.class));
        LOGGER.info("workspace module loaded, enabled={}, provider={}, runtimeAvailable={}",
                config.enabled, config.provider, runtimes.available());
    }

    private WorkspaceConfig config() {
        var config = new WorkspaceConfig();
        config.enabled = "true".equalsIgnoreCase(property("sys.workspace.enabled").orElse("false"));
        config.storageClass = property("sys.workspace.storageClass").orElse(WorkspaceDefaults.STORAGE_CLASS);
        var image = property("sys.workspace.image").orElse("");
        config.image = image.isBlank() ? WorkspaceDefaults.IMAGE : image.trim();
        config.defaultSizeGb = intProperty("sys.workspace.sizeGb", WorkspaceDefaults.SIZE_GB);
        config.maxSizeGb = intProperty("sys.workspace.maxSizeGb", WorkspaceDefaults.MAX_SIZE_GB);
        config.maxWorkspacesPerUser = intProperty("sys.workspace.maxWorkspacesPerUser", WorkspaceDefaults.MAX_WORKSPACES_PER_USER);
        config.maxTotalGbPerUser = intProperty("sys.workspace.maxTotalGbPerUser", WorkspaceDefaults.MAX_TOTAL_GB_PER_USER);
        config.maxRunningPerUser = intProperty("sys.workspace.maxRunningPerUser", WorkspaceDefaults.MAX_RUNNING_PER_USER);
        config.idleSleepMinutes = intProperty("sys.workspace.idleSleepMinutes", WorkspaceDefaults.IDLE_SLEEP_MINUTES);
        config.wakeTimeoutSeconds = intProperty("sys.workspace.wakeTimeoutSeconds", WorkspaceDefaults.WAKE_TIMEOUT_SECONDS);
        return config;
    }

    private int intProperty(String key, int defaultValue) {
        return property(key).map(v -> Integer.parseInt(v.trim())).orElse(defaultValue);
    }
}
