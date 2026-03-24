package ai.core.tool.tools;

import ai.core.AgentRuntimeException;
import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.defaultagents.DefaultExploreAgent;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import core.framework.json.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * author: lim chen
 * date: 2026/3/23
 * description: Todo： TOOL_DESC add `when use ` and General subagent
 */
public class TaskTool extends ToolCall {
    public static final String TOOL_NAME = "task";
    private static final Logger LOGGER = LoggerFactory.getLogger(TaskTool.class);
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

    public long getTimeoutMs() {
        return 30 * 60 * 1000L;
    }

    @Override
    public ToolCallResult execute(String arguments, ExecutionContext context) {
        long startTime = System.currentTimeMillis();
        try {
            var argsMap = JSON.fromJSON(Map.class, arguments);
            var prompt = (String) argsMap.get("prompt");
            var subagentType = (String) argsMap.get("subagent_type");
            var agent = createAgent(subagentType, context);
            var subContext = buildSubContext(subagentType, context);
            agent.run(prompt, subContext);
            return ToolCallResult.completed(agent.getMessages().getLast().content.getFirst().text)
                    .withDuration(System.currentTimeMillis() - startTime);


        } catch (Exception e) {
            var error = "Failed to parse write file arguments: " + e.getMessage();
            return ToolCallResult.failed(error, e)
                    .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    private ExecutionContext buildSubContext(String subagentType, ExecutionContext context) {
        var subContext = ExecutionContext.builder()
                .sessionId("subagent:" + subagentType + "-" + System.currentTimeMillis())
                .userId(context.getUserId())
                .customVariables(context.getCustomVariables())
                .asyncTaskManager(context.getAsyncTaskManager())
                .attachedContent(context.getAttachedContent())
                .persistenceProvider(context.getPersistenceProvider())
                .build();
        subContext.setLlmProvider(context.getLlmProvider());
        subContext.setModel(context.getModel());
        subContext.setStreamingCallback(context.getStreamingCallback());
        subContext.setLifecycles(context.getLifecycle());
        return subContext;
    }

    private Agent createAgent(String subagentType, ExecutionContext context) {
        if (DefaultExploreAgent.AGENT_NAME.equals(subagentType)) {
            return DefaultExploreAgent.of(context.getLlmProvider(), context.getModel(), context.getStreamingCallback(), context.getLifecycle());
        }
        throw new RuntimeException("Unknown subagent type: " + subagentType);
    }

    @Override
    public ToolCallResult execute(String text) {
        throw new AgentRuntimeException("TASK_TOOL_FAILED", "TaskTool requires ExecutionContext");
    }


    public static class Builder extends ToolCall.Builder<Builder, TaskTool> {
        @Override
        protected Builder self() {
            return this;
        }

        public TaskTool build() {
            var subagentType = "- " + String.join(":", DefaultExploreAgent.AGENT_NAME, DefaultExploreAgent.AGENT_DESCRIPTION);
            this.name(TOOL_NAME);
            this.description(TOOL_DESC.replace("%s", subagentType));
            this.parameters(ToolCallParameters.of(
                    ToolCallParameters.ParamSpec.of(String.class, "description", "A short (3-5 words) description of the task").required(),
                    ToolCallParameters.ParamSpec.of(String.class, "prompt", "The task for the agent to perform").required(),
                    ToolCallParameters.ParamSpec.of(String.class, "subagent_type", "The type of specialized agent to use for this task").required()
            ));
            var tool = new TaskTool();
            build(tool);
            return tool;
        }
    }
}
