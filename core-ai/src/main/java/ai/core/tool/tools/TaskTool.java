package ai.core.tool.tools;

import ai.core.AgentRuntimeException;
import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.agent.Task;
import ai.core.defaultagents.DefaultCodeSimplifierAgent;
import ai.core.defaultagents.DefaultExploreAgent;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;

/**
 * author: lim chen
 * date: 2026/3/23
 * description: Todo： TOOL_DESC add `when use ` and General subagent
 */
public class TaskTool extends ToolCall {
    public static final String TOOL_NAME = "task";
    private static final String TOOL_DESC = """
            Launch a new agent to handle complex, multistep tasks autonomously.
            
            Available agent types and the tools they have access to:
            
            %s
            
            When using the Task tool, you must specify a subagent_type parameter to select which agent type to use.
            
            
            Usage notes:
            1. Launch multiple agents concurrently whenever possible, to maximize performance; to do that, use a single message with multiple tool uses
            2. When the agent is done, it will return a single message back to you. The result returned by the agent is not visible to the user. To show the user the result, you should send a text message back to the user with a concise summary of the result. The output includes a task_id you can reuse later to continue the same subagent session.
            3. Each agent invocation starts with a fresh context unless you provide task_id to resume the same subagent session (which continues with its previous messages and tool outputs). When starting fresh, your prompt should contain a highly detailed task description for the agent to perform autonomously and you should specify exactly what information the agent should return back to you in its final and only message to you.
            4. The agent's outputs should generally be trusted
            5. Clearly tell the agent whether you expect it to write code or just to do research (search, file reads, web fetches, etc.), since it is not aware of the user's intent. Tell it how to verify its work if possible (e.g., relevant test commands).
            6. If the agent description mentions that it should be used proactively, then you should try your best to use it without the user having to ask for it first. Use your judgement.
            
            Example usage (NOTE: The agents below are fictional examples for illustration only - use the actual agents listed above):
            
            <example_agent_descriptions>
            "code-reviewer": use this agent after you are done writing a significant piece of code
            "greeting-responder": use this agent when to respond to user greetings with a friendly joke
            </example_agent_description>
            
            <example>
            user: "Please write a function that checks if a number is prime"
            assistant: Sure let me write a function that checks if a number is prime
            assistant: First let me use the Write tool to write a function that checks if a number is prime
            assistant: I'm going to use the Write tool to write the following code:
            <code>
            function isPrime(n) {
              if (n <= 1) return false
              for (let i = 2; i * i <= n; i++) {
                if (n % i === 0) return false
              }
              return true
            }
            </code>
            <commentary>
            Since a significant piece of code was written and the task was completed, now use the code-reviewer agent to review the code
            </commentary>
            assistant: Now let me use the code-reviewer agent to review the code
            assistant: Uses the Task tool to launch the code-reviewer agent
            </example>
            
            <example>
            user: "Hello"
            <commentary>
            Since the user is greeting, use the greeting-responder agent to respond with a friendly joke
            </commentary>
            assistant: "I'm going to use the Task tool to launch the with the greeting-responder agent"
            </example>
            
            """;

    public static Builder builder() {
        return new Builder();
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
        try {
            var argsMap = parseArguments(arguments);
            var prompt = getStringValue(argsMap, "prompt");
            var subagentType = getStringValue(argsMap, "subagent_type");
            var runInBackground = Boolean.TRUE.equals(argsMap.get("run_in_background"));

            var taskManager = context.getTaskManager();
            var description = getStringValue(argsMap, "description");
            var taskId = String.valueOf(argsMap.get("task_id"));
            var subContext = buildSubContext(subagentType, context, taskId, description);
            var subAgent = createAgent(subagentType, subContext);
            if (runInBackground && taskManager != null) {
                var handle = taskManager.submit(taskId, () -> {
                    subAgent.run(prompt, subContext);
                    var lastContent = subAgent.getMessages().getLast().content;
                    return lastContent != null && !lastContent.isEmpty() ? lastContent.getFirst().text : "";
                });
                taskManager.register(new Task(taskId, description, context.getTaskId(), handle.future(), subContext));
                return ToolCallResult.asyncLaunched(taskId, buildAsyncLaunchedNotificationXml(taskId, handle.outputRef(), description, subagentType))
                        .withDuration(System.currentTimeMillis() - startTime);
            } else {
                subAgent.run(prompt, subContext);
                return ToolCallResult.completed(subAgent.getMessages().getLast().content.getFirst().text)
                        .withDuration(System.currentTimeMillis() - startTime);
            }


        } catch (Exception e) {
            var error = "Failed to parse task tool arguments: " + e.getMessage();
            return ToolCallResult.failed(error, e)
                    .withDuration(System.currentTimeMillis() - startTime);
        }
    }


    private String buildAsyncLaunchedNotificationXml(String taskId, String outputRef, String description, String subagentType) {
        var outputRefXml = outputRef != null ? "<output-ref>" + outputRef + "</output-ref>\n" : "";
        var reminder = """
                  Async agent launched successfully.
                  agentId: %s (internal ID - do not mention to user. Use SendMessage with to: %s to continue this agent.)
                  The agent is working in the background. You will be notified automatically when it completes.
                  Do not duplicate this agent's work — avoid working with the same files or topics it is using. Work on non-overlapping tasks, or briefly tell the user what you launched and end
                  your response.
                  output_file: %s
                  If asked, you can check progress before completion by using %s or %s tail on the output file.
                """.formatted(taskId, taskId, outputRef, ReadFileTool.TOOL_NAME, ShellCommandTool.TOOL_NAME);
        return """
                <task-notification>
                <task-id>%s</task-id>
                <task-type>%s</task-type>
                <task-description>%s</task-description>
                <status>%s</status>
                %s
                <system-reminder>%s</system-reminder>
                </task-notification>
                """.formatted(taskId, subagentType, description, "async_launched", outputRefXml, reminder);
    }

    private ExecutionContext buildSubContext(String subagentType, ExecutionContext context, String taskId, String taskName) {
        var subContext = ExecutionContext.builder()
                .sessionId("subagent:" + subagentType + "-" + System.currentTimeMillis())
                .userId(context.getUserId())
                .customVariables(context.getCustomVariables())
                .asyncTaskManager(context.getAsyncTaskManager())
                .attachedContent(context.getAttachedContent())
                .persistenceProvider(context.getPersistenceProvider())
                .taskManager(context.getTaskManager() != null ? context.getTaskManager().createChild() : null)
                .subagentPromptSections(context.getSubagentPromptSections())
                .taskId(taskId)
                .taskName(taskName)
                .build();
        subContext.setLlmProvider(context.getLlmProvider());
        subContext.setModel(context.getModel());
        subContext.setLifecycles(context.getLifecycle());
        subContext.setTokenCostCallback(context.getTokenCostCallback());
        return subContext;
    }

    private Agent createAgent(String subagentType, ExecutionContext context) {
        if (DefaultExploreAgent.AGENT_NAME.equals(subagentType)) {
            return DefaultExploreAgent.of(context.getLlmProvider(), context.getModel(), context.getStreamingCallback(), context.getLifecycle(), context.getSubagentPromptSections());
        }
        if (DefaultCodeSimplifierAgent.AGENT_NAME.equals(subagentType)) {
            return DefaultCodeSimplifierAgent.of(context.getLlmProvider(), context.getModel(), context.getStreamingCallback(), context.getLifecycle(), context.getSubagentPromptSections());
        }
        throw new RuntimeException("Unknown subagent type: " + subagentType);
    }

    public static class Builder extends ToolCall.Builder<Builder, TaskTool> {
        @Override
        protected Builder self() {
            return this;
        }

        public TaskTool build() {
            var subagentType = "- "
                    + String.join(":", DefaultExploreAgent.AGENT_NAME, DefaultExploreAgent.AGENT_DESCRIPTION)
                    + "\n- "
                    + String.join(":", DefaultCodeSimplifierAgent.AGENT_NAME, DefaultCodeSimplifierAgent.AGENT_DESCRIPTION);
            this.name(TOOL_NAME);
            this.description(TOOL_DESC.replace("%s", subagentType));
            this.parameters(ToolCallParameters.of(
                    ToolCallParameters.ParamSpec.of(String.class, "task_id", "a unique id  of the task").required(),
                    ToolCallParameters.ParamSpec.of(String.class, "description", "A short (3-5 words) description of the task").required(),
                    ToolCallParameters.ParamSpec.of(String.class, "prompt", "The task for the agent to perform").required(),
                    ToolCallParameters.ParamSpec.of(String.class, "subagent_type", "The type of specialized agent to use for this task").required(),
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
