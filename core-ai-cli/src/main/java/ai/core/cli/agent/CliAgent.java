package ai.core.cli.agent;

import ai.core.agent.Agent;
import ai.core.agent.AgentBuilder;

import ai.core.agent.ExecutionContext;
import ai.core.agent.SubAgentConfig;
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
import ai.core.cli.plugin.PluginManager;
import ai.core.cli.remote.A2ARemoteAgentConfig;
import ai.core.cli.remote.A2ARemoteServerConfig;
import ai.core.cli.remote.RemoteAgentToolProvider;
import ai.core.cli.subagent.FileSubagentOutputSinkFactory;
import ai.core.cli.task.FileTodoStoreFactory;
import ai.core.cli.trace.HttpTraceUploader;
import ai.core.cli.trace.TraceCollectorLifecycle;
import ai.core.llm.LLMProviders;
import ai.core.media.MediaProvider;
import ai.core.persistence.PersistenceProvider;
import ai.core.prompt.PromptInject;
import ai.core.skill.SkillConfig;
import ai.core.skill.SkillToolProvider;
import ai.core.tool.ToolCall;
import ai.core.tool.mcp.McpToolProvider;
import ai.core.tool.registry.FactoryContext;
import ai.core.tool.registry.ListToolProvider;
import ai.core.tool.registry.ToolRegistryFactory;
import ai.core.tool.tools.AddMcpServerTool;
import ai.core.tool.tools.AskUserTool;
import ai.core.tool.tools.GenerateImageTool;
import ai.core.tool.tools.GenerateVideoTool;
import ai.core.tool.tools.GetVideoStatusTool;
import ai.core.utils.SystemUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
        if (config.imageMediaProvider != null || config.videoMediaProvider != null || config.mediaProvider != null) {
            toolRegistry.registerProvider(ListToolProvider.of(mediaTools(config.imageMediaProvider, config.videoMediaProvider, config.mediaProvider)));
        }
        toolRegistry.registerProvider(buildRemoteAgentProvider(config));

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
        if (config.persistenceProvider != null) builder.persistenceProvider(config.persistenceProvider);
        if (config.modelOverride != null) builder.model(config.modelOverride);
        var multiModalModel = config.providers.getDefaultProvider().config.getMultiModalModel();
        if (multiModalModel != null) builder.multiModalModel(multiModalModel);
        var agent = builder.build();
        var auth = AuthConfig.load();
        // Login-gated, best-effort trace upload. Not logged in -> not attached -> zero overhead.
        if (auth != null && auth.serverUrl() != null && auth.apiKey() != null) {
            agent.addLifecycle(new TraceCollectorLifecycle(new HttpTraceUploader(auth.serverUrl(), auth.apiKey())));
        }
        var execCtx = ExecutionContext.builder()
                .sessionId(config.sessionId)
                .userId(auth != null ? auth.userId() : null)
                .customVariables(Map.of(
                        "workspace", config.workspace.toAbsolutePath().toString(),
                        "media.image.model", config.defaultImageModel != null ? config.defaultImageModel : "",
                        "media.video.model", config.defaultVideoModel != null ? config.defaultVideoModel : ""))
                .persistenceProvider(config.persistenceProvider)
                .subagentOutputSinkFactory(new FileSubagentOutputSinkFactory(config.workspace.resolve(".core-ai/tasks")))
                .todoStoreFactory(new FileTodoStoreFactory(config.workspace.resolve(".core-ai/todos")))
                .promptSections(constructPromptSection(config, hookOutput))
                .build();
        execCtx.setMediaProvider(config.mediaProvider);
        execCtx.setImageMediaProvider(config.imageMediaProvider);
        execCtx.setVideoMediaProvider(config.videoMediaProvider);
        execCtx.setSubAgentConfigs(config.subAgentConfigs);
        execCtx.setAgentProfileRegistry(buildAgentProfileRegistry(config));
        agent.setExecutionContext(execCtx);
        return agent;
    }

    private static void configureLifecycles(AgentBuilder builder, Config config, ScriptHookLifecycle hookLifecycle) {
        if (hookLifecycle != null) builder.addAgentLifecycle(hookLifecycle);
        if (config.memoryEnabled) {
            builder.addAgentLifecycle(new CliMemoryLifecycle(MemoryTriggerService.getInstance(), config.dailyLogsEnabled));
        }
    }

    private static SkillConfig buildSkillConfig(Config config, PluginManager pluginManager) {
        var builder = SkillConfig.builder()
                .source("workspace", config.workspace.resolve(".core-ai/skills").toString(), 100)
                .source("user", userSkillsDir().toString(), 50);

        int priority = 75; // Between workspace (100) and user (50)
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
        return FactoryContext.of(platform, modelProvider, config.todoV2Enabled());
    }

    private static List<ToolCall> cliUserTools(Config config) {
        return List.of(
                AskUserTool.builder().questionHandler(config.askUserHandler()).build(),
                AddMcpServerTool.builder().build());
    }

    private static List<ToolCall> mediaTools(MediaProvider imageMediaProvider, MediaProvider videoMediaProvider, MediaProvider mediaProvider) {
        var imageProvider = imageMediaProvider != null ? imageMediaProvider : mediaProvider;
        var videoProvider = videoMediaProvider != null ? videoMediaProvider : mediaProvider;
        var tools = new ArrayList<ToolCall>();
        if (imageProvider != null) tools.add(GenerateImageTool.builder().build());
        if (videoProvider != null) {
            tools.add(GenerateVideoTool.builder(videoProvider).build());
            tools.add(GetVideoStatusTool.builder().build());
        }
        return tools;
    }

    private static RemoteAgentToolProvider buildRemoteAgentProvider(Config config) {
        return RemoteAgentToolProvider.discover(config.remoteAgents(), config.remoteServers(), config.a2aAutoDiscover());
    }

    private static Path userSkillsDir() {
        return Path.of(System.getProperty("user.home"), ".core-ai", "skills");
    }

    private static void configureSystemPrompt(AgentBuilder builder, Config config, String hookOutput) {
        builder.systemPromptSections(constructPromptSection(config, hookOutput));
    }

    private static List<PromptInject> constructPromptSection(Config config, String hookOutput) {
        List<PromptInject> sections = new ArrayList<>();
        sections.add(config.coding ? new CliAgentCodeBasePrompt() : new CliAgentBasePrompt());
        sections.add(new CliAgentEnvironmentPrompt(config.workspace));
        sections.add(new CliAgentGitStatusPrompt(config.workspace));
        sections.add(new CliAgentInstructionsPrompt(config.workspace));
        if (config.memoryEnabled) {
            var content = new MdMemoryProvider(config.workspace).load();
            sections.add(new MemorySystemPrompt(content));
        }
        sections.add(new CliAgentHookPrompt(hookOutput));
        return sections;
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
                            String defaultVideoModel) {
    }
}
