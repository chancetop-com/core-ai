package ai.core.server;

import ai.core.api.server.workspace.WorkspaceWebService;
import ai.core.server.blob.ObjectStorageServiceResolver;
import ai.core.server.sandbox.SandboxService;
import ai.core.server.sandbox.WorkspaceSandboxFactory;
import ai.core.server.sandbox.agentsandbox.AgentSandboxApiVersion;
import ai.core.server.sandbox.agentsandbox.AgentSandboxClient;
import ai.core.server.sandbox.agentsandbox.AgentSandboxProvider;
import ai.core.server.sandbox.agentsandbox.SandboxPodPlacement;
import ai.core.server.sandbox.kubernetes.KubernetesClient;
import ai.core.server.session.ChatSessionStore;
import ai.core.server.workspace.KubernetesWorkspaceProvisioner;
import ai.core.server.workspace.PgWorkspaceBindingRow;
import ai.core.server.workspace.PgWorkspaceBindingStore;
import ai.core.server.workspace.PgWorkspaceEventRow;
import ai.core.server.workspace.PgWorkspaceEventStore;
import ai.core.server.workspace.PgWorkspaceJobLeaseStore;
import ai.core.server.workspace.PgWorkspaceLeaseRow;
import ai.core.server.workspace.PgWorkspaceLeaseStore;
import ai.core.server.workspace.PgWorkspaceRow;
import ai.core.server.workspace.PgWorkspaceStore;
import ai.core.server.workspace.PgWorkspaceUsageRow;
import ai.core.server.workspace.PgWorkspaceUsageStore;
import ai.core.server.workspace.PgWorkspaceVolumeRow;
import ai.core.server.workspace.PgWorkspaceVolumeStore;
import ai.core.server.workspace.WorkspaceConfig;
import ai.core.server.workspace.WorkspaceDefaults;
import ai.core.server.workspace.WorkspaceLifecycleJob;
import ai.core.server.workspace.WorkspaceRuntimeManager;
import ai.core.server.workspace.WorkspaceService;
import ai.core.server.workspace.WorkspaceSessionBinder;
import ai.core.server.workspace.WorkspaceStores;
import ai.core.server.workspace.WorkspaceUsageService;
import ai.core.server.workspace.HttpWorkspaceArchiveClient;
import ai.core.server.workspace.PgWorkspaceArchiveRow;
import ai.core.server.workspace.PgWorkspaceArchiveStore;
import ai.core.server.workspace.PgWorkspaceArchiveSummaryRow;
import ai.core.server.workspace.WorkspaceArchiveService;
import ai.core.server.web.WorkspaceWebServiceImpl;
import core.framework.module.Module;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
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
    @SuppressFBWarnings("HES_LOCAL_EXECUTOR_SERVICE")
    protected void initialize() {
        var config = config();
        var stores = bindStores(config.enabled);

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
        var usageService = bind(new WorkspaceUsageService(stores.workspaces(), stores.bindings(), stores.usage(), runtimes));
        var wakeExecutor = Executors.newFixedThreadPool(4, runnable -> {
            var thread = new Thread(runnable, "workspace-wake");
            thread.setDaemon(true);
            return thread;
        });
        var workspaceService = bind(new WorkspaceService(stores, provisioner, runtimes, usageService, config, wakeExecutor));
        var storageResolver = bean(ObjectStorageServiceResolver.class);
        var archiveService = bind(new WorkspaceArchiveService(workspaceService,
                runtimes.available() ? new HttpWorkspaceArchiveClient() : null,
                storageResolver::resolve));
        workspaceService.attachArchiveService(archiveService);
        bind(new WorkspaceSessionBinder(workspaceService));
        var sandboxService = bean(SandboxService.class);
        bind(new WorkspaceSandboxFactory(bean(WorkspaceService.class), bean(ChatSessionStore.class), provider));
        sandboxService.workspaceSandboxFactory(bean(WorkspaceSandboxFactory.class));
        api().service(WorkspaceWebService.class, bind(WorkspaceWebServiceImpl.class));
        if (config.enabled) {
            bind(PgWorkspaceJobLeaseStore.class);
            schedule().fixedRate("workspace-lifecycle", bind(WorkspaceLifecycleJob.class), Duration.ofMinutes(1));
        }
        LOGGER.info("workspace module loaded, enabled={}, provider={}, runtimeAvailable={}",
                config.enabled, config.provider, runtimes.available());
    }

    private WorkspaceStores bindStores(boolean enabled) {
        if (!enabled) {
            return new WorkspaceStores(null, null, null, null, null, null, null);
        }
        db().view(PgWorkspaceRow.class);
        db().view(PgWorkspaceVolumeRow.class);
        db().view(PgWorkspaceBindingRow.class);
        db().view(PgWorkspaceEventRow.class);
        db().view(PgWorkspaceLeaseRow.class);
        db().view(PgWorkspaceUsageRow.class);
        db().view(PgWorkspaceArchiveRow.class);
        db().view(PgWorkspaceArchiveSummaryRow.class);
        return new WorkspaceStores(bind(new PgWorkspaceStore()), bind(new PgWorkspaceVolumeStore()),
                bind(new PgWorkspaceBindingStore()), bind(new PgWorkspaceEventStore()),
                bind(new PgWorkspaceLeaseStore()), bind(new PgWorkspaceUsageStore()),
                bind(new PgWorkspaceArchiveStore()));
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
        config.bootstrapTimeoutSeconds = intProperty("sys.workspace.bootstrapTimeoutSeconds", WorkspaceDefaults.BOOTSTRAP_TIMEOUT_SECONDS);
        config.startTimeoutSeconds = intProperty("sys.workspace.startTimeoutSeconds", WorkspaceDefaults.START_TIMEOUT_SECONDS);
        config.leaseTtlSeconds = intProperty("sys.workspace.leaseTtlSeconds", WorkspaceDefaults.LEASE_TTL_SECONDS);
        config.leaseWaitSeconds = intProperty("sys.workspace.leaseWaitSeconds", WorkspaceDefaults.LEASE_WAIT_SECONDS);
        config.minRuntimeSeconds = intProperty("sys.workspace.minRuntimeSeconds", WorkspaceDefaults.MIN_RUNTIME_SECONDS);
        config.purgeAfterDays = intProperty("sys.workspace.purgeAfterDays", WorkspaceDefaults.PURGE_AFTER_DAYS);
        config.usageSampleMinutes = intProperty("sys.workspace.usageSampleMinutes", WorkspaceDefaults.USAGE_SAMPLE_MINUTES);
        config.archiveRetentionDays = intProperty("sys.workspace.archive.retentionDays", WorkspaceDefaults.ARCHIVE_RETENTION_DAYS);
        var archiveContainer = property("sys.workspace.archive.container").orElse("");
        config.archiveContainer = archiveContainer.isBlank() ? WorkspaceDefaults.ARCHIVE_CONTAINER : archiveContainer.trim();
        return config;
    }

    private int intProperty(String key, int defaultValue) {
        var value = property(key).orElse(null);
        return value == null ? defaultValue : Integer.parseInt(value.trim());
    }
}
