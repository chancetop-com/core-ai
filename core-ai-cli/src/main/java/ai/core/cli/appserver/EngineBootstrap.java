package ai.core.cli.appserver;

import ai.core.agent.SubAgentConfig;
import ai.core.bootstrap.AgentBootstrap;
import ai.core.bootstrap.BootstrapResult;
import ai.core.bootstrap.PropertiesFileSource;
import ai.core.cli.CliAppHelper;
import ai.core.cli.a2a.A2ARemoteAgentConfig;
import ai.core.cli.a2a.A2ARemoteAgentConfigLoader;
import ai.core.cli.a2a.A2ARemoteServerConfig;
import ai.core.cli.auth.AuthConfig;
import ai.core.cli.auth.RuntimeAuthConfig;
import ai.core.cli.config.ModelRegistry;
import ai.core.cli.log.CliLogger;
import ai.core.cli.memory.MemoryTriggerService;
import ai.core.cli.schedule.FileScheduledTaskStore;
import ai.core.cli.utils.PathUtils;
import ai.core.context.CompressionConfig;
import ai.core.llm.LLMProviderType;
import ai.core.media.MediaProvider;
import ai.core.schedule.ScheduledTaskStore;
import ai.core.session.FileRuleBasedPermissionStore;
import ai.core.session.FileSessionPersistence;
import ai.core.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Properties;

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

    private static PropertiesFileSource loadProperties(Path configFile) {
        var resolved = configFile != null ? configFile : PathUtils.DEFAULT_CONFIG;
        if (Files.exists(resolved)) {
            return PropertiesFileSource.fromFile(resolved);
        }
        LOGGER.info("app-server: no config file at {}, using defaults", resolved);
        return new PropertiesFileSource(new Properties());
    }

    public final Path workspace;
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
    public final String defaultImageModel;
    public final String defaultVideoModel;
    public final CompressionConfig compressionConfig;
    public final ModelRegistry modelRegistry;
    public final ScheduledTaskStore scheduledTaskStore;

    public EngineBootstrap(Path workspace, Path configFile) {
        this.workspace = workspace.toAbsolutePath();
        CliLogger.initialize(this.workspace, "app-server");
        this.props = loadProperties(configFile);
        CliAppHelper.mergeWorkspaceConfig(props, workspace);
        CliAppHelper.injectLiteLLMFallback(props);
        var bootstrap = new AgentBootstrap(props);
        CliAppHelper.registerMcpLoadingListener();
        this.result = bootstrap.initialize();
        registerAuthListener(result);
        CliAppHelper.configureInlineImages(result.liteLLMProvider);
        applyActiveProvider();
        this.maxTurn = intFlag("agent.max.turn", 100);
        this.memoryEnabled = boolFlag("agent.memory.enabled", true);
        this.dailyLogsEnabled = boolFlag("agent.memory.daily.logs.enabled", false);
        this.coding = boolFlag("agent.coding.enabled", false);
        this.todoV2Enabled = boolFlag("agent.todo.v2.enabled", false);
        this.promptExtractionEnabled = boolFlag("agent.memory.prompt.extraction", false);
        this.a2aAutoDiscover = boolFlag("a2a.autoDiscover", false);
        props.property("agent.memory.timezone").map(ZoneId::of).ifPresent(MemoryTriggerService::setTimezone);
        this.remoteAgents = A2ARemoteAgentConfigLoader.load(props);
        this.remoteServers = A2ARemoteAgentConfigLoader.loadServers(props);
        this.sessionPersistence = new FileSessionPersistence(PathUtils.sessionsDir(this.workspace));
        this.sessionManager = new SessionManager(sessionPersistence);
        this.permissionStore = CliAppHelper.whiteToolsPermissionStore(this.workspace);
        this.subAgentConfigs = CliAppHelper.parseSubAgentConfig(props, result.llmProviders);
        this.imageMediaProvider = CliAppHelper.imageMediaProvider(props);
        this.videoMediaProvider = CliAppHelper.videoMediaProvider(props);
        this.mediaProvider = imageMediaProvider != null ? imageMediaProvider : videoMediaProvider;
        this.defaultImageModel = props.property("media.image.model").orElse(null);
        this.defaultVideoModel = props.property("media.video.model").orElse(null);
        this.compressionConfig = CliAppHelper.compressionConfig(props);
        this.modelRegistry = new ModelRegistry(result.llmProviders, props);
        this.scheduledTaskStore = new FileScheduledTaskStore(
                Path.of(System.getProperty("user.home"), ".core-ai", "schedules.json"));
        LOGGER.info("app-server engine initialized, workspace={}", this.workspace);
    }

    private int intFlag(String name, int fallback) {
        return props.property(name).map(Integer::parseInt).orElse(fallback);
    }

    private boolean boolFlag(String name, boolean fallback) {
        return props.property(name).map(Boolean::parseBoolean).orElse(fallback);
    }

    private void applyActiveProvider() {
        props.property("active.provider").ifPresent(name -> {
            var type = LLMProviderType.fromName(name);
            if (type != null && result.llmProviders.getProvider(type) != null) {
                result.llmProviders.setDefaultProvider(type);
            }
        });
    }
}
