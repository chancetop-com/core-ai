package ai.core.tool.tools;

import ai.core.AgentRuntimeException;
import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.agent.Task;
import ai.core.agent.profile.AgentProfile;
import ai.core.agent.profile.AgentProfileRegistry;
import ai.core.agent.subagent.SubagentThread;
import ai.core.agent.subagent.SubagentThreadKind;
import ai.core.agent.subagent.SubagentThreadRegistry;
import ai.core.agent.subagent.SubagentThreadUnavailableException;
import ai.core.defaultagents.DefaultCodeSimplifierAgent;
import ai.core.defaultagents.DefaultExploreAgent;
import ai.core.defaultagents.DefaultGeneralAgent;
import ai.core.defaultagents.DeepResearchAgent;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.ReasoningEffort;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Tool;
import ai.core.prompt.PromptInject;
import ai.core.session.BackgroundTaskManager;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import ai.core.tool.registry.ToolRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * author: lim chen
 * date: 2026/3/23
 * description: task
 */
public class TaskTool extends ToolCall {
    public static final String TOOL_NAME = "task";

    public static Builder builder() {
        return new Builder();
    }

    private static String newTaskId() {
        return String.format("sa-%08x", ThreadLocalRandom.current().nextInt());
    }

    private static String extractLastAssistantText(Agent agent) {
        var messages = agent.getMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            var message = messages.get(i);
            if (message.role == RoleType.ASSISTANT) {
                var text = message.getTextContent();
                return text != null ? text : "";
            }
        }
        return "";
    }

    @Override
    public Tool toTool(ExecutionContext context) {
        var threads = context == null
                ? List.<SubagentThread>of()
                : context.getSubagentThreads().list().stream().filter(thread -> thread.kind() == SubagentThreadKind.TASK).toList();
        setDescription(TaskToolPrompt.buildDescription(resolveAgentTypes(context), threads));
        return super.toTool(context);
    }

    private String resolveAgentTypes(ExecutionContext context) {
        if (context != null && context.getAgentProfileRegistry() != null) {
            var profiles = context.getAgentProfileRegistry().listAll();
            if (!profiles.isEmpty()) {
                return TaskToolPrompt.profileAgentTypes(profiles);
            }
        }
        return TaskToolPrompt.builtinAgentTypes();
    }

    @Override
    public long getTimeoutMs() {
        return 30 * 60 * 1000L;
    }

    @Override
    public ToolCallResult execute(String text) {
        throw new AgentRuntimeException("TASK_TOOL_FAILED", "TaskTool requires ExecutionContext");
    }

    @Override
    public ToolCallResult execute(String arguments, ExecutionContext context) {
        long startTime = System.currentTimeMillis();
        Map<String, Object> argsMap;
        try {
            argsMap = parseArguments(arguments);
        } catch (Exception e) {
            return ToolCallResult.failed("Failed to parse task tool arguments: " + e.getMessage(), e)
                    .withDuration(System.currentTimeMillis() - startTime);
        }
        var call = TaskCall.of(argsMap);
        try {
            return dispatch(call, context).withDuration(System.currentTimeMillis() - startTime);
        } catch (RuntimeException e) {
            return ToolCallResult.failed("Subagent '" + call.taskId() + "' execution error: " + e.getMessage(), e)
                    .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    /**
     * Resolves the thread the call addresses — an existing one to continue, a new one to start — and
     * hands it to a run once its gate is taken.
     */
    private ToolCallResult dispatch(TaskCall call, ExecutionContext context) {
        var registry = context.getSubagentThreads();
        var thread = call.taskIdProvided() ? registry.find(call.taskId()).orElse(null) : null;
        // an id no thread answers to starts a new subagent under that id; the result footer says so explicitly
        var noEarlierContext = thread == null && call.taskIdProvided();
        if (thread != null) {
            var invalid = validateContinuation(thread, call.subagentType());
            if (invalid != null) {
                return ToolCallResult.failed(invalid);
            }
            if (call.description() != null && !call.description().isBlank()) {
                thread.updateDescription(call.description());
            }
        } else if (call.subagentType() == null || call.subagentType().isBlank()) {
            return ToolCallResult.failed("subagent_type is required to start a new subagent");
        }
        var subContext = buildSubContext(thread != null ? thread.type() : call.subagentType(), context, call.taskId(), call.description());
        var activeThread = thread == null ? startNewThread(call, registry, subContext, context) : thread;
        if (!registry.tryAcquire(activeThread)) {
            return ToolCallResult.failed("subagent " + call.taskId() + " is still running; wait for its task-notification before continuing it, or cancel it with async_task_output");
        }
        return runThread(new ThreadRun(activeThread, call, registry, context, subContext, noEarlierContext));
    }

    private SubagentThread startNewThread(TaskCall call, SubagentThreadRegistry registry, ExecutionContext subContext, ExecutionContext context) {
        Agent created;
        try {
            created = createAgent(call.subagentType(), subContext);
        } catch (RuntimeException e) {
            throw new SubagentThreadUnavailableException("Cannot start subagent '" + call.subagentType() + "': " + e.getMessage(), e);
        }
        return registry.register(call.taskId(), SubagentThreadKind.TASK, call.subagentType(), call.description(),
                resolveModel(call.subagentType(), context), created);
    }

    private ToolCallResult runThread(ThreadRun run) {
        var gateOwned = true;
        try {
            var agent = resolveThreadAgent(run.thread(), run.registry(), run.subContext());
            agent.resetCancellation();
            gateOwned = false;
            var taskManager = run.context().getTaskManager();
            if (taskManager != null && run.call().runInBackground()) {
                return launchBackground(run, agent, taskManager);
            }
            return runSync(run, agent);
        } catch (SubagentThreadUnavailableException e) {
            return ToolCallResult.failed("Cannot continue subagent " + run.call().taskId() + ": " + e.getMessage());
        } finally {
            if (gateOwned) {
                run.registry().abort(run.thread());
            }
        }
    }

    private Agent resolveThreadAgent(SubagentThread thread, SubagentThreadRegistry registry, ExecutionContext subContext) {
        var agent = thread.agent();
        if (agent != null) {
            return agent;
        }
        return registry.rehydrate(thread, () -> createAgent(thread.type(), subContext));
    }

    private ToolCallResult runSync(ThreadRun run, Agent agent) {
        String text;
        try {
            text = runAgent(agent, run.call().prompt(), run.subContext());
        } finally {
            releaseThread(run.registry(), run.thread(), agent);
        }
        return ToolCallResult.completed(text + "\n\n" + TaskToolPrompt.threadFooter(run.thread(), run.noEarlierContext()));
    }

    private ToolCallResult launchBackground(ThreadRun run, Agent agent, BackgroundTaskManager taskManager) {
        var submitted = false;
        try {
            var handle = taskManager.submit(run.call().taskId(),
                    () -> runInBackgroundAndCollect(run, agent), run.context().getCancellationToken());
            submitted = true;
            taskManager.register(new Task(run.call().taskId(), run.call().description(), run.context().getTaskId(), handle.future(), run.subContext()));
            return ToolCallResult.asyncLaunched(run.call().taskId(), TaskToolPrompt.asyncLaunchedNotificationXml(
                    run.call().taskId(), handle.outputRef(), run.call().description(), run.thread().type(), run.thread().model()));
        } finally {
            if (!submitted) {
                releaseThread(run.registry(), run.thread(), agent);
            }
        }
    }

    private String runInBackgroundAndCollect(ThreadRun run, Agent agent) {
        String text;
        try {
            text = runAgent(agent, run.call().prompt(), run.subContext());
        } finally {
            releaseThread(run.registry(), run.thread(), agent);
        }
        return text + "\n\n" + TaskToolPrompt.threadFooter(run.thread(), run.noEarlierContext());
    }

    private String runAgent(Agent agent, String prompt, ExecutionContext subContext) {
        agent.run(prompt, subContext);
        return extractLastAssistantText(agent);
    }

    private void releaseThread(SubagentThreadRegistry registry, SubagentThread thread, Agent agent) {
        registry.release(thread, agent, extractLastAssistantText(agent));
        registry.persist(thread);
        registry.evictIfNeeded();
    }

    private String validateContinuation(SubagentThread thread, String subagentType) {
        if (thread.kind() != SubagentThreadKind.TASK) {
            return "task_id '" + thread.taskId() + "' belongs to subagent tool '" + thread.type()
                    + "'; call that tool to use it, or omit task_id to start a new subagent";
        }
        if (subagentType != null && !subagentType.isBlank() && !subagentType.equals(thread.type())) {
            return "task_id " + thread.taskId() + " belongs to subagent type '" + thread.type() + "', not '" + subagentType + "'";
        }
        return null;
    }

    private ExecutionContext buildSubContext(String subagentType, ExecutionContext context, String taskId, String taskName) {
        var subContext = ExecutionContext.builder()
                .sessionId("subagent:" + subagentType + "-" + System.currentTimeMillis())
                .userId(context.getUserId())
                .caller(context.getCaller())
                .customVariables(context.getCustomVariables())
                .asyncTaskManager(context.getAsyncTaskManager())
                .attachedContent(context.getAttachedContent())
                .persistenceProvider(context.getPersistenceProvider())
                .taskManager(context.getTaskManager() != null ? context.getTaskManager().createChild() : null)
                .promptSections(context.getPromptSections())
                .sandbox(context.getSandbox())
                .taskId(taskId)
                .taskName(taskName)
                .build();
        // nested subagents share the session's threads: same registry, same persistence, same gate
        subContext.setSubagentThreads(context.getSubagentThreads());
        subContext.setLlmProvider(context.getLlmProvider());
        subContext.setModel(context.getModel());
        subContext.setLifecycles(context.getLifecycle());
        subContext.setTokenCostCallback(context.getTokenCostCallback());
        subContext.setSubAgentConfigs(context.getSubAgentConfigs());
        subContext.setToolRegistry(context.getToolRegistry());
        subContext.setAgentProfileRegistry(context.getAgentProfileRegistry());
        return subContext;
    }

    private Agent createAgent(String subagentType, ExecutionContext context) {
        var llmProvider = resolveLlmProvider(subagentType, context);
        var model = resolveModel(subagentType, context);
        var maxTurnNumber = resolveMaxTurnNumber(subagentType, context);
        var toolRegistry = context.getToolRegistry();
        AgentProfileRegistry profileRegistry = context.getAgentProfileRegistry();
        if (profileRegistry != null) {
            AgentProfile profile = profileRegistry.get(subagentType).orElse(null);
            if (profile != null) {
                return buildAgentFromProfile(profile, llmProvider, model, maxTurnNumber, toolRegistry, context);
            }
            throw new RuntimeException("Unknown subagent type: " + subagentType);
        }
        return createBuiltinAgent(subagentType, llmProvider, model, maxTurnNumber, toolRegistry, context);
    }

    private Agent createBuiltinAgent(String subagentType, LLMProvider llmProvider, String model, int maxTurnNumber, ToolRegistry toolRegistry, ExecutionContext context) {
        if (DeepResearchAgent.AGENT_NAME.equals(subagentType)) {
            return DeepResearchAgent.of(toolRegistry, llmProvider, model, context, maxTurnNumber);
        }
        if (DefaultExploreAgent.AGENT_NAME.equals(subagentType)) {
            return DefaultExploreAgent.of(toolRegistry, llmProvider, model, context, maxTurnNumber);
        }
        if (DefaultCodeSimplifierAgent.AGENT_NAME.equals(subagentType)) {
            return DefaultCodeSimplifierAgent.of(toolRegistry, llmProvider, model, context, maxTurnNumber);
        }
        if (DefaultGeneralAgent.AGENT_NAME.equals(subagentType)) {
            return DefaultGeneralAgent.of(toolRegistry, llmProvider, model, context, maxTurnNumber);
        }
        throw new RuntimeException("Unknown subagent type: " + subagentType);
    }

    private Agent buildAgentFromProfile(AgentProfile profile, LLMProvider llmProvider, String model, int maxTurnNumber, ToolRegistry toolRegistry, ExecutionContext context) {
        var builder = Agent.builder()
                .name(profile.name())
                .description(profile.description())
                .systemPrompt(profile.systemPrompt() != null ? profile.systemPrompt() : "")
                .llmProvider(llmProvider)
                .model(profile.model() != null ? profile.model() : model)
                .maxTurn(profile.maxTurnNumber() != null ? profile.maxTurnNumber() : maxTurnNumber)
                .streamingCallback(context.getStreamingCallback())
                .agentLifecycle(context.getLifecycle())
                .toolRegistry(toolRegistry);
        if (profile.tools() != null && !profile.tools().isEmpty()) {
            builder.toolNames(new ArrayList<>(profile.tools()));
        }
        builder.systemPromptSections(context.getPromptSections().stream()
                .filter(s -> s.type() == PromptInject.SectionType.ENVIRONMENT
                        || s.type() == PromptInject.SectionType.INSTRUCTIONS
                        || s.type() == PromptInject.SectionType.MEMORY)
                .toList());
        if (profile.reasoningEffort() != null) {
            var effort = ReasoningEffort.fromString(profile.reasoningEffort());
            if (effort != null) builder.reasoningEffort(effort);
        }
        if (profile.temperature() != null) {
            builder.temperature(profile.temperature());
        }
        return builder.build();
    }

    private int resolveMaxTurnNumber(String subagentType, ExecutionContext context) {
        var configs = context.getSubAgentConfigs();
        if (configs != null) {
            var config = configs.get(subagentType);
            if (config != null && config.maxTurnNumber() != null) {
                return config.maxTurnNumber();
            }
        }
        return 200;
    }

    private String resolveModel(String subagentType, ExecutionContext context) {
        var configs = context.getSubAgentConfigs();
        if (configs != null) {
            var config = configs.get(subagentType);
            if (config != null && config.model() != null && !config.model().isBlank()) {
                return config.model();
            }
        }
        return context.getModel();
    }

    private LLMProvider resolveLlmProvider(String subagentType, ExecutionContext context) {
        var configs = context.getSubAgentConfigs();
        if (configs != null) {
            var config = configs.get(subagentType);
            if (config != null && config.llmProvider() != null) {
                return config.llmProvider();
            }
        }
        return context.getLlmProvider();
    }

    private record TaskCall(String taskId, boolean taskIdProvided, String subagentType, String description,
                            String prompt, boolean runInBackground) {
        static TaskCall of(Map<String, Object> argsMap) {
            var requestedTaskId = getStringValue(argsMap, "task_id");
            var taskIdProvided = requestedTaskId != null && !requestedTaskId.isBlank();
            return new TaskCall(
                    taskIdProvided ? requestedTaskId : newTaskId(),
                    taskIdProvided,
                    getStringValue(argsMap, "subagent_type"),
                    getStringValue(argsMap, "description"),
                    getStringValue(argsMap, "prompt"),
                    Boolean.TRUE.equals(argsMap.get("run_in_background")));
        }
    }

    private record ThreadRun(SubagentThread thread, TaskCall call, SubagentThreadRegistry registry,
                             ExecutionContext context, ExecutionContext subContext, boolean noEarlierContext) {
    }

    public static class Builder extends ToolCall.Builder<Builder, TaskTool> {
        @Override
        protected Builder self() {
            return this;
        }

        public TaskTool build() {
            this.name(TOOL_NAME);
            this.sourceType("subagent");
            this.description(TaskToolPrompt.buildDescription(TaskToolPrompt.builtinAgentTypes(), List.of()));
            this.parameters(ToolCallParameters.of(
                    ToolCallParameters.ParamSpec.of(String.class, "task_id",
                                    "Identifier of a subagent. Omit to start a new subagent; pass an existing id (see Active subagents in this description) to continue that subagent with its full previous context.")
                            .optional(),
                    ToolCallParameters.ParamSpec.of(String.class, "description", "A short (3-5 words) description of the task").required(),
                    ToolCallParameters.ParamSpec.of(String.class, "prompt", "The task for the agent to perform").required(),
                    ToolCallParameters.ParamSpec.of(String.class, "subagent_type",
                                    "The type of specialized agent to use for this task. Required when starting a new subagent; may be omitted when continuing an existing one.")
                            .optional(),
                    ToolCallParameters.ParamSpec.of(Boolean.class, "run_in_background",
                            "Set to true to run this agent in the background."
                                    + "Setting up background processing can effectively support multiple tasks running in parallel. "
                                    + "Returns immediately with a taskId. "
                                    + "You will receive a <task-notification> when the agent completes. "
                                    + "Launch multiple agents concurrently by calling task() with run_in_background=true multiple times in a single message.")
            ));
            var tool = new TaskTool();
            build(tool);
            return tool;
        }
    }
}
