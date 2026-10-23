package ai.core.cli.agent;

import ai.core.agent.Agent;
import ai.core.agent.AgentBuilder;

import ai.core.agent.ExecutionContext;
import ai.core.agent.SubAgentConfig;
import ai.core.agent.budget.RunBudget;
import ai.core.agent.profile.AgentProfileRegistry;
import ai.core.agent.profile.BuiltinAgentProfileProvider;
import ai.core.cli.agent.profile.FilesystemAgentProfileProvider;
import ai.core.cli.auth.AuthConfig;
import ai.core.cli.hook.HookConfig;
import ai.core.cli.hook.ScriptHookLifecycle;
import ai.core.cli.hook.ScriptHookRunner;
import ai.core.cli.memory.CliMemoryLifecycle;
import ai.core.cli.memory.MdMemoryProvider;
import ai.core.cli.memory.MemorySystemPrompt;
import ai.core.cli.memory.MemoryTriggerService;
import ai.core.cli.media.MediaModelCatalog;
import ai.core.cli.plugin.PluginManager;
import ai.core.cli.a2a.A2ARemoteAgentConfig;
import ai.core.cli.a2a.A2ARemoteServerConfig;
import ai.core.cli.a2a.RemoteAgentToolProvider;
import ai.core.cli.helper.HelperAgentToolProvider;
import ai.core.cli.helper.HelperEntry;
import ai.core.cli.helper.HelperService;
import ai.core.cli.subagent.FileSubagentOutputSinkFactory;
import ai.core.cli.task.FileTodoStoreFactory;
import ai.core.cli.trace.HttpTraceUploader;
import ai.core.cli.trace.TraceCollectorLifecycle;
import ai.core.llm.LLMProviders;
import ai.core.media.CoreAiGatewayMediaProvider;
import ai.core.media.MediaProvider;
import ai.core.persistence.PersistenceProvider;
import ai.core.prompt.PromptInject;
import ai.core.skill.SkillConfig;
import ai.core.skill.SkillToolProvider;
import ai.core.tool.ToolCall;
import ai.core.tool.mcp.McpToolProvider;
import ai.core.tool.registry.FactoryContext;
import ai.core.tool.registry.ListToolProvider;
import ai.core.tool.registry.ToolRegistry;
import ai.core.tool.registry.ToolRegistryFactory;
import ai.core.tool.tools.AddMcpServerTool;
import ai.core.tool.tools.AskUserTool;
import ai.core.tool.tools.GenerateImageTool;
import ai.core.tool.tools.GenerateVideoTool;
import ai.core.tool.tools.GetVideoStatusTool;
import ai.core.tool.tools.MediaModelHint;
import ai.core.utils.SystemUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * @author stephen
 */
public class CliAgent {

    public static Agent of(Config config) {
        var pluginManager = PluginManager.getInstance(Path.of(System.getProperty("user.home"), ".core-ai"));
        var toolRegistry = ToolRegistryFactory.create(buildFactoryContext(config));
        toolRegistry.registerProvider(new McpToolProvider());
        toolRegistry.registerProvider(new SkillToolProvider(buildSkillConfig(config, pluginManager), config.workspace.toAbsolutePath().toString()));
        toolRegistry.registerProvider(ListToolProvider.of(cliUserTools(config)));
        var mediaTools = mediaTools(config);
        if (!mediaTools.isEmpty()) {
            toolRegistry.registerProvider(ListToolProvider.of(mediaTools));
        }
        toolRegistry.registerProvider(buildRemoteAgentProvider(config));
        var helperProvider = buildHelperProvider(config, toolRegistry);
        if (helperProvider != null) {
            toolRegistry.registerProvider(helperProvider);
        }

        var hookConfig = HookConfig.load(config.workspace, pluginManager);
        var hookLifecycle = hookConfig.isEmpty() ? null
                : new ScriptHookLifecycle(hookConfig, new ScriptHookRunner(config.workspace));
        String hookOutput = hookLifecycle != null ? hookLifecycle.runSessionStartHooks() : "";
        var builder = Agent.builder()
                .llmProvider(config.providers.getDefaultProvider())
                .maxTurn(config.maxTurn)
                .toolRegistry(toolRegistry)
                .temperature(0.8);
        configureSystemPrompt(builder, config, hookOutput);
        configureLifecycles(builder, config, hookLifecycle);
        builder.compression(config.compressionConfig);
        if (config.persistenceProvider != null) builder.persistenceProvider(config.persistenceProvider);
        if (config.modelOverride != null) builder.model(config.modelOverride);
        var multiModalModel = config.providers.getDefaultProvider().config.getMultiModalModel();
        if (multiModalModel != null) builder.multiModalModel(multiModalModel);
        var agent = builder.build();
        var auth = AuthConfig.load();
        // Login-gated, best-effort trace upload. Not logged in -> not attached -> zero overhead.
        if (auth != null && auth.serverUrl() != null && auth.apiKey() != null) {
            agent.addLifecycle(new TraceCollectorLifecycle(
                    new HttpTraceUploader(auth.serverUrl(), auth.apiKey(), auth.insecure()), config.clientType));
        }
        configureExecutionContext(agent, config, auth, hookOutput);
        return agent;
    }

    private static void configureExecutionContext(Agent agent, Config config, AuthConfig auth, String hookOutput) {
        var imageProvider = config.imageMediaProvider != null ? config.imageMediaProvider : config.mediaProvider;
        var videoProvider = config.videoMediaProvider != null ? config.videoMediaProvider : config.mediaProvider;
        var imageModel = effectiveImageModel(imageProvider, config.defaultImageModel, config.mediaCatalog);
        var videoModel = effectiveVideoModel(videoProvider, config.defaultVideoModel, config.mediaCatalog);
        var editModels = imageProvider instanceof CoreAiGatewayMediaProvider
                ? modelIds(imageEditModels(config.mediaCatalog)) : List.<String>of();
        var execCtx = ExecutionContext.builder()
                .sessionId(config.sessionId)
                .userId(auth != null ? auth.userId() : null)
                .customVariables(Map.of(
                        "workspace", config.workspace.toAbsolutePath().toString(),
                        "media.image.model", blankToEmpty(imageModel),
                        "media.image.default.model", blankToEmpty(imageModel),
                        "media.image.edit.model", blankToEmpty(effectiveImageEditModel(imageProvider,
                                config.defaultImageModel, config.defaultImageEditModel, config.mediaCatalog)),
                        "media.image.edit.models", editModels,
                        "media.video.model", blankToEmpty(videoModel),
                        "media.video.default.model", blankToEmpty(videoModel)))
                .persistenceProvider(config.persistenceProvider)
                .subagentOutputSinkFactory(new FileSubagentOutputSinkFactory(config.workspace.resolve(".core-ai/tasks")))
                .todoStoreFactory(new FileTodoStoreFactory(config.workspace.resolve(".core-ai/todos")))
                .promptSections(constructPromptSection(config, hookOutput))
                .runBudget(config.runBudget())
                .build();
        execCtx.setMediaProvider(config.mediaProvider);
        execCtx.setImageMediaProvider(config.imageMediaProvider);
        execCtx.setVideoMediaProvider(config.videoMediaProvider);
        execCtx.setSubAgentConfigs(config.subAgentConfigs);
        execCtx.setAgentProfileRegistry(buildAgentProfileRegistry(config));
        agent.setExecutionContext(execCtx);
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String effectiveImageModel(MediaProvider provider, String configured, MediaModelCatalog catalog) {
        if (configured != null && !configured.isBlank()) return configured.trim();
        if (!(provider instanceof CoreAiGatewayMediaProvider) || catalog == null) return null;
        return catalog.defaultImageModel();
    }

    private static String effectiveVideoModel(MediaProvider provider, String configured, MediaModelCatalog catalog) {
        if (configured != null && !configured.isBlank()) return configured.trim();
        if (!(provider instanceof CoreAiGatewayMediaProvider) || catalog == null) return null;
        return catalog.defaultVideoModel();
    }

    private static String effectiveImageEditModel(MediaProvider provider, String configuredImageModel,
                                                  String configuredEditModel, MediaModelCatalog catalog) {
        if (configuredEditModel != null && !configuredEditModel.isBlank()) return configuredEditModel.trim();
        if (!(provider instanceof CoreAiGatewayMediaProvider)) {
            return effectiveImageModel(provider, configuredImageModel, null);
        }
        var imageModel = effectiveImageModel(provider, configuredImageModel, catalog);
        var editIds = modelIds(imageEditModels(catalog));
        if (imageModel != null && editIds.contains(imageModel)) return imageModel;
        var catalogEditModel = catalog == null ? null : catalog.defaultImageEditModel();
        return catalogEditModel != null ? catalogEditModel : imageModel;
    }

    private static List<MediaModelHint> imageModels(MediaModelCatalog catalog) {
        return catalog == null ? List.of() : catalog.imageModels();
    }

    private static List<MediaModelHint> imageEditModels(MediaModelCatalog catalog) {
        return catalog == null ? List.of() : catalog.imageEditModels();
    }

    private static List<MediaModelHint> videoModels(MediaModelCatalog catalog) {
        return catalog == null ? List.of() : catalog.videoModels();
    }

    private static List<String> modelIds(List<MediaModelHint> models) {
        var ids = new ArrayList<String>(models.size());
        for (var model : models) ids.add(model.modelId());
        return List.copyOf(ids);
    }

    private static void configureLifecycles(AgentBuilder builder, Config config, ScriptHookLifecycle hookLifecycle) {
        builder.addAgentLifecycle(new RunBudgetLifecycle(config.runBudget()));
        if (hookLifecycle != null) builder.addAgentLifecycle(hookLifecycle);
        if (config.memoryEnabled) {
            builder.addAgentLifecycle(new CliMemoryLifecycle(MemoryTriggerService.getInstance(config.workspace), config.dailyLogsEnabled));
        }
    }

    private static SkillConfig buildSkillConfig(Config config, PluginManager pluginManager) {
        // Lower priority value wins in SkillRegistry: workspace overrides plugins overrides user.
        var builder = SkillConfig.builder()
                .source("workspace", config.workspace.resolve(".core-ai/skills").toString(), 50)
                .source("user", userSkillsDir().toString(), 100);

        int priority = 75; // Between workspace (50) and user (100)
        for (var source : pluginManager.getEnabledPluginSkillSources()) {
            builder.source("plugin:" + source[0], source[1], priority);
        }

        return builder.build();
    }

    private static FactoryContext buildFactoryContext(Config config) {
        var platform = SystemUtil.detectPlatform();
        var defaultProvider = config.providers().getDefaultProvider();
        var providerType = config.providers().getProviderType(defaultProvider);
        var modelProvider = providerType != null ? providerType.name() : null;
        return FactoryContext.of(platform, modelProvider, config.todoV2Enabled(), config.disabledTools());
    }

    private static List<ToolCall> cliUserTools(Config config) {
        var tools = new ArrayList<ToolCall>();
        tools.add(AskUserTool.builder().questionHandler(config.askUserHandler()).build());
        tools.add(AddMcpServerTool.builder().build());
        if (config.scheduledTaskStore() != null) {
            tools.add(ai.core.tool.tools.ScheduledTaskTool.builder(config.scheduledTaskStore()).build());
        }
        return List.copyOf(tools);
    }

    /**
     * A managed gateway needs at least one known model: the server rejects a request without a model, and a
     * tool the agent can see but never call successfully wastes a turn. A custom endpoint is registered as
     * before, with the static description, because the server does not know its models.
     */
    static List<ToolCall> mediaTools(Config config) {
        var catalog = config.mediaCatalog;
        // the first session on a cold engine may race the warm-up fetch; bound the wait so the tools
        // are registered from the real catalog instead of appearing only on the second session
        if (catalog != null) catalog.ensureLoaded(3_000);
        var imageProvider = config.imageMediaProvider != null ? config.imageMediaProvider : config.mediaProvider;
        var videoProvider = config.videoMediaProvider != null ? config.videoMediaProvider : config.mediaProvider;
        var imageModel = effectiveImageModel(imageProvider, config.defaultImageModel, catalog);
        var videoModel = effectiveVideoModel(videoProvider, config.defaultVideoModel, catalog);
        var tools = new ArrayList<ToolCall>();
        if (imageToolAvailable(imageProvider, imageModel)) {
            var managed = imageProvider instanceof CoreAiGatewayMediaProvider;
            var description = GenerateImageTool.buildDescription(
                    managed ? imageModels(catalog) : List.of(),
                    managed ? imageEditModels(catalog) : List.of());
            if (imageModel != null) description += "\n\nDefault image model for this session: " + imageModel;
            tools.add(GenerateImageTool.builder().description(description).build());
        }
        if (videoToolAvailable(videoProvider, videoModel)) {
            var managed = videoProvider instanceof CoreAiGatewayMediaProvider;
            var description = GenerateVideoTool.buildDescription(managed ? videoModels(catalog) : List.of());
            if (videoModel != null) description += "\n\nDefault video model for this session: " + videoModel;
            tools.add(GenerateVideoTool.builder(videoProvider).description(description).build());
            tools.add(GetVideoStatusTool.builder().build());
        }
        return tools;
    }

    private static boolean imageToolAvailable(MediaProvider provider, String model) {
        if (provider == null) return false;
        return !(provider instanceof CoreAiGatewayMediaProvider) || model != null;
    }

    private static boolean videoToolAvailable(MediaProvider provider, String model) {
        if (provider == null) return false;
        return !(provider instanceof CoreAiGatewayMediaProvider) || model != null;
    }

    private static RemoteAgentToolProvider buildRemoteAgentProvider(Config config) {
        return RemoteAgentToolProvider.discover(config.remoteAgents(), config.remoteServers(), config.a2aAutoDiscover());
    }

    /**
     * Mounts enabled helpers as delegation tools. The reserved-name snapshot is taken from the
     * registry first, so a colliding helper is renamed instead of being silently shadowed.
     */
    private static HelperAgentToolProvider buildHelperProvider(Config config, ToolRegistry toolRegistry) {
        var helpers = config.helpers();
        if (helpers == null || helpers.isEmpty()) {
            return null;
        }
        var reserved = toolRegistry.materialize().getDispatchMap().keySet();
        return HelperAgentToolProvider.create(helpers, reserved, HelperService::clientOrNull, HelperService::uploaderOrNull);
    }

    private static Path userSkillsDir() {
        return Path.of(System.getProperty("user.home"), ".core-ai", "skills");
    }

    private static void configureSystemPrompt(AgentBuilder builder, Config config, String hookOutput) {
        builder.systemPromptSections(constructPromptSection(config, hookOutput));
    }

    private static List<PromptInject> constructPromptSection(Config config, String hookOutput) {
        List<PromptInject> sections = new ArrayList<>();
        sections.add(identityPrompt(config.coding, config.clientType));
        sections.add(new CliAgentPersonalityPrompt());
        sections.add(new CliAgentEnvironmentPrompt(config.workspace, config.clientType));
        if (config.runBudget() != null) {
            sections.add(new RunBudgetPrompt(config.runBudget()));
        }
        if (managedBrowser()) {
            sections.add(new CliAgentManagedBrowserPrompt());
        }
        if (computerUseEnabled()) {
            sections.add(new CliAgentComputerUsePrompt());
        }
        sections.add(new CliAgentGitStatusPrompt(config.workspace));
        sections.add(new CliAgentInstructionsPrompt(config.workspace));
        if (config.memoryEnabled) {
            var content = new MdMemoryProvider(config.workspace).load();
            sections.add(new MemorySystemPrompt(content));
        }
        sections.add(new CliAgentHookPrompt(hookOutput));
        return sections;
    }

    /**
     * The desktop app is a general assistant, so it gets its own identity and never the CLI coding prompt — even
     * though it reads the same {@code agent.coding.enabled} flag from the config file the CLI uses.
     */
    static PromptInject identityPrompt(boolean coding, String clientType) {
        if (CliClientType.isDesktop(clientType)) return new CliAgentAssistantPrompt();
        return coding ? new CliAgentCodeBasePrompt() : new CliAgentBasePrompt();
    }

    /** The desktop app spawns the engine with this flag; its built-in browser pane is the only browser. */
    private static boolean managedBrowser() {
        return "1".equals(System.getenv("CORE_AI_BROWSER_MANAGED"));
    }

    /** The desktop app enables this after the user turns on Computer use in Settings. */
    private static boolean computerUseEnabled() {
        return "1".equals(System.getenv("CORE_AI_COMPUTER_USE"));
    }

    private static AgentProfileRegistry buildAgentProfileRegistry(Config config) {
        var registry = new AgentProfileRegistry();
        registry.addProvider(new BuiltinAgentProfileProvider());

        Path userAgentsDir = Path.of(System.getProperty("user.home"), ".core-ai", "agents");
        if (Files.isDirectory(userAgentsDir)) {
            registry.addProvider(new FilesystemAgentProfileProvider(userAgentsDir, 50));
        }

        Path workspaceAgentsDir = config.workspace.resolve(".core-ai").resolve("agents");
        if (Files.isDirectory(workspaceAgentsDir)) {
            registry.addProvider(new FilesystemAgentProfileProvider(workspaceAgentsDir, 100));
        }

        return registry;
    }

    public record Config(LLMProviders providers, String modelOverride, int maxTurn,
                         PersistenceProvider persistenceProvider, Path workspace,
                         Function<String, String> askUserHandler,
                         boolean memoryEnabled,
                         boolean dailyLogsEnabled,
                         boolean coding,
                         boolean todoV2Enabled,
                         String sessionId,
                         List<A2ARemoteAgentConfig> remoteAgents,
                         List<A2ARemoteServerConfig> remoteServers,
                          Map<String, SubAgentConfig> subAgentConfigs,
                            boolean a2aAutoDiscover,
                             MediaProvider mediaProvider,
                             MediaProvider imageMediaProvider,
                             MediaProvider videoMediaProvider,
                             String defaultImageModel,
                             String defaultVideoModel,
                             String defaultImageEditModel,
                             ai.core.schedule.ScheduledTaskStore scheduledTaskStore,
                             ai.core.context.CompressionConfig compressionConfig,
                             String clientType,
                             List<HelperEntry> helpers,
                             MediaModelCatalog mediaCatalog,
                             RunBudget runBudget,
                             Set<String> disabledTools) {
    }
}
