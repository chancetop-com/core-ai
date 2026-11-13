package ai.core.tool.tools;

import ai.core.AgentRuntimeException;
import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.agent.SubAgentConfig;
import ai.core.agent.Task;
import ai.core.agent.profile.AgentProfile;
import ai.core.agent.subagent.SubagentModelRef;
import ai.core.agent.subagent.SubagentModelUnavailableException;
import ai.core.agent.subagent.SubagentThread;
import ai.core.agent.subagent.SubagentThreadKind;
import ai.core.agent.subagent.SubagentThreadRegistry;
import ai.core.agent.subagent.SubagentThreadUnavailableException;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Tool;
import ai.core.session.BackgroundTaskManager;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;

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

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    @Override
    public Tool toTool(ExecutionContext context) {
        var threads = context == null
                ? List.<SubagentThread>of()
                : context.getSubagentThreads().list().stream().filter(thread -> thread.kind() == SubagentThreadKind.TASK).toList();
        setDescription(TaskToolPrompt.buildDescription(resolveAgentTypes(context), threads, SubagentModelSelector.currentModel(context)));
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

        var type = thread != null ? thread.type() : call.subagentType();
        var profile = resolveProfile(type, context);
        var config = resolveConfig(type, context);
        SubagentModelSelector.ModelSelection selection;
        try {
            selection = SubagentModelSelector.select(call.model(), thread, profile, config, context);
        } catch (SubagentModelUnavailableException e) {
            if (thread != null && blank(call.model()) && !blank(thread.model())) {
                return ToolCallResult.failed("[SUBAGENT_THREAD_MODEL_UNAVAILABLE] subagent " + thread.taskId()
                        + " was created with model '" + e.model() + "', which is no longer available. "
                        + "Start a new subagent or restore the provider configuration.");
            }
            return ToolCallResult.failed(SubagentModelSelector.error(e, context));
        }

        // an explicit model switches a live thread under the run gate; a legacy thread gets its model recorded after it
        var subContext = buildSubContext(type, context, call.taskId(), call.description());
        var activeThread = thread == null
                ? startNewThread(call, registry, subContext, type, profile, selection)
                : thread;
        if (!registry.tryAcquire(activeThread)) {
            return ToolCallResult.failed("subagent " + call.taskId() + " is still running; wait for its task-notification before continuing it, or cancel it with async_task_output");
        }
        return runThread(new ThreadRun(activeThread, call, registry, context, subContext, noEarlierContext, selection, profile));
    }

    private SubagentThread startNewThread(TaskCall call, SubagentThreadRegistry registry, ExecutionContext subContext,
                                          String type, AgentProfile profile, SubagentModelSelector.ModelSelection selection) {
        Agent created;
        try {
            created = SubagentAgentFactory.create(type, subContext, profile, selection);
        } catch (RuntimeException e) {
            throw new SubagentThreadUnavailableException("Cannot start subagent '" + type + "': " + e.getMessage(), e);
        }
        return registry.registerWithModel(call.taskId(), SubagentThreadKind.TASK, type, call.description(),
                new SubagentModelRef(selection.model(), selection.providerName()), created);
    }

    private AgentProfile resolveProfile(String type, ExecutionContext context) {
        var registry = context.getAgentProfileRegistry();
        return registry == null ? null : registry.get(type).orElse(null);
    }

    private SubAgentConfig resolveConfig(String type, ExecutionContext context) {
        var configs = context.getSubAgentConfigs();
        return configs == null ? null : configs.get(type);
    }

    private ToolCallResult runThread(ThreadRun run) {
        var gateOwned = true;
        try {
            var agent = resolveThreadAgent(run);
            agent.resetCancellation();
            if (("CALL".equals(run.selection().source()) || blank(run.thread().model())) && !blank(run.selection().model())) {
                run.thread().updateModel(run.selection().model(), run.selection().providerName());
            }
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

    private Agent resolveThreadAgent(ThreadRun run) {
        var thread = run.thread();
        var agent = thread.agent();
        if (agent != null) {
            if (SubagentModelSelector.shouldApplyToLiveAgent(run.selection())) {
                SubagentModelSelector.applySelection(agent, run.selection());
            }
            return agent;
        }
        return run.registry().rehydrate(thread,
                () -> SubagentAgentFactory.create(thread.type(), run.subContext(), run.profile(), run.selection()));
    }

    private ToolCallResult runSync(ThreadRun run, Agent agent) {
        String text;
        try {
            text = runAgent(agent, run.call().prompt(), run.subContext());
        } finally {
            releaseThread(run, agent);
        }
        return ToolCallResult.completed(text + "\n\n" + TaskToolPrompt.threadFooter(run.thread(), run.noEarlierContext()));
    }

    private ToolCallResult launchBackground(ThreadRun run, Agent agent, BackgroundTaskManager taskManager) {
        var submitted = false;
        try {
            var handle = taskManager.submit(run.call().taskId(),
                    () -> runInBackgroundAndCollect(run, agent), run.context().getCancellationToken(),
                    () -> run.registry().abort(run.thread()), run.call().notifyOnCompletion());
            submitted = true;
            taskManager.register(new Task(run.call().taskId(), run.call().description(), run.context().getTaskId(), handle.future(), run.subContext()));
            return ToolCallResult.asyncLaunched(run.call().taskId(), TaskToolPrompt.asyncLaunchedNotificationXml(
                    run.call().taskId(), handle.outputRef(), run.call().description(), run.thread().type(), run.thread().model(),
                    run.call().notifyOnCompletion()));
        } finally {
            if (!submitted) {
                releaseThread(run, agent);
            }
        }
    }

    private String runInBackgroundAndCollect(ThreadRun run, Agent agent) {
        String text;
        try {
            text = runAgent(agent, run.call().prompt(), run.subContext());
        } finally {
            releaseThread(run, agent);
        }
        return text + "\n\n" + TaskToolPrompt.threadFooter(run.thread(), run.noEarlierContext());
    }

    private String runAgent(Agent agent, String prompt, ExecutionContext subContext) {
        agent.run(prompt, subContext);
        return extractLastAssistantText(agent);
    }

    private void releaseThread(ThreadRun run, Agent agent) {
        var thread = run.thread();
        var effectiveModel = SubagentModelSelector.effectiveModel(agent);
        if (!blank(effectiveModel)) {
            thread.updateModel(effectiveModel, SubagentModelSelector.selectionProvider(run.selection(), thread, agent));
        }
        run.registry().release(thread, agent, extractLastAssistantText(agent));
        run.registry().persist(thread);
        run.registry().evictIfNeeded();
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
                .runBudget(context.getRunBudget() == null ? null : context.getRunBudget().childScope())
                .build();
        // nested subagents share the session's threads: same registry, same persistence, same gate
        subContext.setSubagentThreads(context.getSubagentThreads());
        subContext.setLlmProvider(context.getLlmProvider());
        subContext.setModel(context.getModel());
        subContext.setDecisionProvider(context.getDecisionProvider());
        subContext.setLifecycles(context.getLifecycle());
        subContext.setTokenCostCallback(context.getTokenCostCallback());
        subContext.setSubAgentConfigs(context.getSubAgentConfigs());
        subContext.setSubagentModelResolver(context.getSubagentModelResolver());
        subContext.setToolRegistry(context.getToolRegistry());
        subContext.setAgentProfileRegistry(context.getAgentProfileRegistry());
        return subContext;
    }

    private record TaskCall(String taskId, boolean taskIdProvided, String subagentType, String description,
                            String prompt, String model, boolean runInBackground, boolean notifyOnCompletion) {
        static TaskCall of(Map<String, Object> argsMap) {
            var requestedTaskId = getStringValue(argsMap, "task_id");
            var taskIdProvided = requestedTaskId != null && !requestedTaskId.isBlank();
            return new TaskCall(
                    taskIdProvided ? requestedTaskId : newTaskId(),
                    taskIdProvided,
                    getStringValue(argsMap, "subagent_type"),
                    getStringValue(argsMap, "description"),
                    getStringValue(argsMap, "prompt"),
                    getStringValue(argsMap, "model"),
                    Boolean.TRUE.equals(argsMap.get("run_in_background")),
                    !Boolean.FALSE.equals(argsMap.get("notify")));
        }
    }

    private record ThreadRun(SubagentThread thread, TaskCall call, SubagentThreadRegistry registry,
                             ExecutionContext context, ExecutionContext subContext, boolean noEarlierContext,
                             SubagentModelSelector.ModelSelection selection, AgentProfile profile) {
    }

    public static class Builder extends ToolCall.Builder<Builder, TaskTool> {
        @Override
        protected Builder self() {
            return this;
        }

        public TaskTool build() {
            this.name(TOOL_NAME);
            this.sourceType("subagent");
            this.description(TaskToolPrompt.buildDescription(TaskToolPrompt.builtinAgentTypes(), List.of(), null));
            this.parameters(ToolCallParameters.of(
                    ToolCallParameters.ParamSpec.of(String.class, "task_id",
                                    "Identifier of a subagent. Omit to start a new subagent; pass an existing id (see Active subagents in this description) to continue that subagent with its full previous context.")
                            .optional(),
                    ToolCallParameters.ParamSpec.of(String.class, "description", "A short (3-5 words) description of the task").required(),
                    ToolCallParameters.ParamSpec.of(String.class, "prompt", "The task for the agent to perform").required(),
                    ToolCallParameters.ParamSpec.of(String.class, "subagent_type",
                                    "The type of specialized agent to use for this task. Required when starting a new subagent; may be omitted when continuing an existing one.")
                            .optional(),
                    ToolCallParameters.ParamSpec.of(String.class, "model",
                                    "Optional model id for this subagent run. Omit to inherit your current model. "
                                            + "An unavailable model fails the call instead of falling back. "
                                            + "When continuing a subagent with task_id, a different model switches that subagent to it.")
                            .optional(),
                    ToolCallParameters.ParamSpec.of(Boolean.class, "run_in_background",
                            "Set to true to run this agent in the background."
                                    + "Setting up background processing can effectively support multiple tasks running in parallel. "
                                    + "Returns immediately with a taskId. "
                                    + "You will receive a <task-notification> when the agent completes. "
                                    + "Launch multiple agents concurrently by calling task() with run_in_background=true multiple times in a single message.")
                            .optional(),
                    ToolCallParameters.ParamSpec.of(Boolean.class, "notify",
                            "Only for run_in_background=true. Set to false when you only need this agent's side effect and will not act on its result; "
                                    + "you can still poll it with async_task_output. Defaults to true.")
                            .optional()
            ));

            var tool = new TaskTool();
            build(tool);
            return tool;
        }
    }
}
