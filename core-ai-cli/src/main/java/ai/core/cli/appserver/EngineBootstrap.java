package ai.core.cli.appserver;

import ai.core.agent.SubAgentConfig;
import ai.core.agent.subagent.SubagentModelResolver;
import ai.core.bootstrap.AgentBootstrap;
import ai.core.bootstrap.BootstrapResult;
import ai.core.bootstrap.PropertiesFileSource;
import ai.core.cli.CliAppHelper;
import ai.core.cli.a2a.A2ARemoteAgentConfig;
import ai.core.cli.a2a.A2ARemoteAgentConfigLoader;
import ai.core.cli.a2a.A2ARemoteServerConfig;
import ai.core.cli.agent.CliSubagentModelResolver;
import ai.core.cli.agent.CliToolPolicy;
import ai.core.cli.auth.AuthConfig;
import ai.core.cli.auth.RuntimeAuthConfig;
import ai.core.cli.config.LlmProviderStore;
import ai.core.cli.config.ModelRegistry;
import ai.core.cli.config.ProviderWiring;
import ai.core.cli.config.ServerModelCatalog;
import ai.core.cli.log.CliLogger;
import ai.core.cli.media.MediaModelCatalog;
import ai.core.cli.memory.MemoryTriggerService;
import ai.core.cli.schedule.FileScheduledTaskStore;
import ai.core.cli.utils.PathUtils;
import ai.core.cli.workspace.WorkspaceTrust;
import ai.core.context.CompressionConfig;
import ai.core.media.MediaProvider;
import ai.core.schedule.ScheduledTaskStore;
import ai.core.session.EngineLock;
import ai.core.session.FileRuleBasedPermissionStore;
import ai.core.session.FileSessionPersistence;
import ai.core.session.SessionLock;
import ai.core.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * One-time bootstrap for the app-server engine: the same config chain and wiring as {@code CliApp}
 * (config file -> workspace overrides -> saved-auth LLM fallback), but headless: no terminal UI,
 * no banner, stdout stays clean for the protocol frames.
 *
 * @author stephen
 */
public class EngineBootstrap {
    private static final Logger LOGGER = LoggerFactory.getLogger(EngineBootstrap.class);

    private static void registerAuthListener(BootstrapResult result) {
        var auth = AuthConfig.load();
        if (auth != null && auth.apiKey() != null) {
            RuntimeAuthConfig.instance().update(auth.serverUrl() + RuntimeAuthConfig.LLM_PROXY_PATH, auth.apiKey());
        }
        if (result.liteLLMProvider != null) {
            RuntimeAuthConfig.instance().addListener(() -> {
                var runtime = RuntimeAuthConfig.instance();
                if (runtime.isConfigured()) {
                    result.liteLLMProvider.updateCredentials(runtime.serverUrl(), runtime.apiKey());
                }
            });
        }
    }

    private static Path prepareSessionsDir(Path workspace) {
        var dir = WorkspaceDirs.ensure(workspace);
        Housekeeping.runEngine(dir);
        return dir;
    }

    private static WorkspaceTrust.Evaluation evaluateTrust(Path workspace) {
        var evaluation = WorkspaceTrust.evaluate(workspace);
        System.setProperty(WorkspaceTrust.RESTRICTED_PROPERTY, String.valueOf(!evaluation.trusted()));
        return evaluation;
    }

    public final Path workspace;
    public final Path sessionsDir;
    public final SessionLock engineLock;
    public final PropertiesFileSource props;
    public final BootstrapResult result;
    public final int maxTurn;
    public final boolean memoryEnabled;
    public final boolean dailyLogsEnabled;
    public final boolean coding;
    public final boolean todoV2Enabled;
    public final boolean promptExtractionEnabled;
    public final boolean a2aAutoDiscover;
    public final List<A2ARemoteAgentConfig> remoteAgents;
    public final List<A2ARemoteServerConfig> remoteServers;
    public final FileSessionPersistence sessionPersistence;
    public final SessionManager sessionManager;
    public final FileRuleBasedPermissionStore permissionStore;
    public final Map<String, SubAgentConfig> subAgentConfigs;
    public final MediaProvider mediaProvider;
    public final MediaProvider imageMediaProvider;
    public final MediaProvider videoMediaProvider;
    public final MediaModelCatalog mediaModelCatalog;
    public final String defaultImageModel;
    public final String defaultVideoModel;
    public final CompressionConfig compressionConfig;
    public final ModelRegistry modelRegistry;
    public final LlmProviderStore providerStore;
    public final SubagentModelResolver subagentModelResolver;
    public final ScheduledTaskStore scheduledTaskStore;
    public final Set<String> disabledTools;
    public final WorkspaceTrust.Evaluation workspaceTrust;
    public final boolean workspaceTrusted;

    public EngineBootstrap(Path workspace, Path configFile) {
        this.workspace = workspace.toAbsolutePath();
        this.workspaceTrust = evaluateTrust(this.workspace);
        this.workspaceTrusted = workspaceTrust.trusted();
        this.sessionsDir = prepareSessionsDir(this.workspace);
        var acquire = EngineLock.tryAcquire(sessionsDir, "engine");
        if (acquire instanceof SessionLock.AcquireResult.Busy busy) {
            throw new EngineAlreadyRunningException(busy.holder());
        }
        this.engineLock = ((SessionLock.AcquireResult.Acquired) acquire).lock();
        CliLogger.initialize(this.workspace, "app-server");
        this.props = loadProperties(configFile, !workspaceTrusted);
        var bootstrap = new AgentBootstrap(props);
        CliAppHelper.registerMcpLoadingListener();
        this.result = bootstrap.initialize();
        this.providerStore = initializeProviders(result);
        this.maxTurn = intFlag("agent.max.turn", 100);
        this.memoryEnabled = workspaceTrusted && boolFlag("agent.memory.enabled", true);
        this.dailyLogsEnabled = workspaceTrusted && boolFlag("agent.memory.daily.logs.enabled", false);
        this.coding = boolFlag("agent.coding.enabled", false);
        this.todoV2Enabled = boolFlag("agent.todo.v2.enabled", false);
        this.promptExtractionEnabled = boolFlag("agent.memory.prompt.extraction", false);
        this.a2aAutoDiscover = boolFlag("a2a.autoDiscover", false);
        props.property("agent.memory.timezone").map(ZoneId::of).ifPresent(MemoryTriggerService::setTimezone);
        this.remoteAgents = A2ARemoteAgentConfigLoader.load(props);
        this.remoteServers = A2ARemoteAgentConfigLoader.loadServers(props);
        this.sessionPersistence = new FileSessionPersistence(sessionsDir.toString());
        this.sessionManager = new SessionManager(sessionPersistence);
        this.permissionStore = CliAppHelper.whiteToolsPermissionStore(this.workspace);
        this.subAgentConfigs = CliAppHelper.parseSubAgentConfig(props, result.llmProviders);
        this.imageMediaProvider = CliAppHelper.imageMediaProvider(props);
        this.videoMediaProvider = CliAppHelper.videoMediaProvider(props);
        this.mediaProvider = CliAppHelper.sharedMediaProvider(props);
        this.mediaModelCatalog = createMediaModelCatalog();
        this.defaultImageModel = props.property("media.image.model").orElse(null);
        this.defaultVideoModel = props.property("media.video.model").orElse(null);
        this.compressionConfig = CliAppHelper.compressionConfig(props);
        this.modelRegistry = new ModelRegistry(result.llmProviders, props, providerStore);
        this.subagentModelResolver = new CliSubagentModelResolver(modelRegistry, result.llmProviders);
        this.scheduledTaskStore = new FileScheduledTaskStore(
                Path.of(System.getProperty("user.home"), ".core-ai", "schedules.json"), this.workspace);
        this.disabledTools = CliToolPolicy.disabledTools(props);
        LOGGER.info("app-server engine initialized, workspace={}", this.workspace);
    }

    /** Execution entry points call this before running a turn, a compaction or a scheduled task. */
    public void requireTrusted(String action) {
        if (workspaceTrusted) return;
        throw RpcException.business("TRUST_REQUIRED",
                "this workspace is not trusted; trust it in Settings → Plugins before " + action,
                "reason", workspaceTrust.reason());
    }

    private LlmProviderStore initializeProviders(BootstrapResult result) {
        var store = LlmProviderStore.load();
        ProviderWiring.apply(store, result, props);
        registerAuthListener(result);
        CliAppHelper.configureInlineImages(result, props);
        ProviderWiring.applyDefault(store, result.llmProviders, props);
        ServerModelCatalog.installContextWindowResolver();
        return store;
    }

    private PropertiesFileSource loadProperties(Path configFile, boolean restricted) {
        var resolved = configFile != null ? configFile : PathUtils.DEFAULT_CONFIG;
        PropertiesFileSource props;
        if (Files.exists(resolved)) {
            props = PropertiesFileSource.fromFile(resolved);
        } else {
            LOGGER.info("app-server: no config file at {}, using defaults", resolved);
            props = new PropertiesFileSource(new Properties());
        }
        if (!restricted) {
            CliAppHelper.mergeWorkspaceConfig(props, workspace);
        } else {
            // A restricted workspace must not contribute provider overrides, MCP servers or other
            // executable configuration. Global MCP is also suppressed: the design treats hooks/MCP
            // as executable components that require an explicit trust decision.
            props.removeProperty("mcp.servers.json");
            LOGGER.info("app-server: workspace is restricted; workspace config and MCP are not loaded");
        }
        CliAppHelper.injectLiteLLMFallback(props);
        return props;
    }

    private MediaModelCatalog createMediaModelCatalog() {
        var catalog = CliAppHelper.mediaModelCatalog(imageMediaProvider, videoMediaProvider, AuthConfig.load());
        if (catalog != null) {
            LOGGER.info("media gateway configured: base={}", CliAppHelper.mediaGatewayBase(props));
        }
        return catalog;
    }

    private int intFlag(String name, int fallback) {
        return props.property(name).map(Integer::parseInt).orElse(fallback);
    }

    private boolean boolFlag(String name, boolean fallback) {
        return props.property(name).map(Boolean::parseBoolean).orElse(fallback);
    }

    public void close() {
        engineLock.close();
    }
}
