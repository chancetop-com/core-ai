package ai.core.tool.tools;

import ai.core.agent.profile.AgentProfile;
import ai.core.agent.subagent.SubagentThread;
import ai.core.agent.subagent.SubagentThreadState;
import ai.core.defaultagents.DefaultCodeSimplifierAgent;
import ai.core.defaultagents.DefaultExploreAgent;
import ai.core.defaultagents.DefaultGeneralAgent;
import ai.core.defaultagents.DeepResearchAgent;

import java.util.List;

/**
 * Prompt text of the task tool: the tool description with its per-turn agent type list and active
 * subagent list, the continuation footer of a subagent result, and the background launch
 * notification.
 *
 * @author stephen
 */
final class TaskToolPrompt {
    static final String TOOL_DESC = """
            Launch a new agent to handle complex, multistep tasks autonomously.

            Available agent types and the tools they have access to:

            %s

            When using the Task tool, you must specify a subagent_type parameter to select which agent type to use.


            Usage notes:
            1. Launch multiple agents concurrently whenever possible, to maximize performance; to do that, use a single message with multiple tool uses
            2. When the agent is done, it will return a single message back to you. The result returned by the agent is not visible to the user. To show the user the result, you should send a text message back to the user with a concise summary of the result. The result ends with a [subagent task_id=...] line — reuse that task_id to continue the same subagent session later.
            3. Each agent invocation starts with a fresh context unless you provide an existing task_id to continue a subagent (which keeps its previous messages and tool outputs). When starting fresh, your prompt should contain a highly detailed task description for the agent to perform autonomously and you should specify exactly what information the agent should return back to you in its final and only message to you.
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
    private static final int MAX_THREAD_LINES = 20;
    private static final int MAX_DESCRIPTION_LENGTH = 80;
    private static final String ACTIVE_SUBAGENTS_HEADER = """

            Active subagents in this session (pass task_id to continue one):
            """;
    private static final String ACTIVE_SUBAGENTS_FOOTER = """
            Continue an existing subagent when the follow-up concerns what it already explored or built; start a new one for unrelated work.
            A continued subagent remembers its earlier tool outputs but NOT changes made since — tell it what changed and ask it to re-verify anything critical.
            """;

    static String builtinAgentTypes() {
        return "- "
                + String.join(":", DeepResearchAgent.AGENT_NAME, DeepResearchAgent.AGENT_DESCRIPTION)
                + "\n- "
                + String.join(":", DefaultExploreAgent.AGENT_NAME, DefaultExploreAgent.AGENT_DESCRIPTION)
                + "\n- "
                + String.join(":", DefaultCodeSimplifierAgent.AGENT_NAME, DefaultCodeSimplifierAgent.AGENT_DESCRIPTION)
                + "\n- "
                + String.join(":", DefaultGeneralAgent.AGENT_NAME, DefaultGeneralAgent.AGENT_DESCRIPTION);
    }

    static String profileAgentTypes(List<AgentProfile> profiles) {
        var sb = new StringBuilder();
        for (var profile : profiles) {
            sb.append("- ").append(profile.name()).append(": ").append(profile.description()).append('\n');
        }
        return sb.toString();
    }

    static String buildDescription(String agentTypes, List<SubagentThread> threads) {
        var description = TOOL_DESC.replace("%s", agentTypes);
        if (threads.isEmpty()) return description;
        var sb = new StringBuilder(description);
        sb.append(ACTIVE_SUBAGENTS_HEADER);
        var limit = Math.min(threads.size(), MAX_THREAD_LINES);
        for (int i = 0; i < limit; i++) {
            sb.append(threadLine(threads.get(i))).append('\n');
        }
        if (threads.size() > limit) {
            sb.append("- …and ").append(threads.size() - limit).append(" more (their task_ids are in their earlier results)\n");
        }
        sb.append('\n').append(ACTIVE_SUBAGENTS_FOOTER);
        return sb.toString();
    }

    /**
     * The model-visible continuation handle appended to every subagent result. Rides the result
     * string, so the same text reaches the main agent synchronously and through a background
     * completion notification.
     */
    static String threadFooter(SubagentThread thread, boolean noEarlierContext) {
        var sb = new StringBuilder(256).append("[subagent task_id=").append(thread.taskId())
                .append(" type=").append(thread.type())
                .append(" runs=").append(thread.runCount())
                .append(" context≈").append(formatTokens(thread.contextTokens())).append(" tokens");
        if (noEarlierContext) {
            sb.append(" (no earlier context found for this id)");
        }
        sb.append(" — pass this task_id to `task` to ask it follow-up questions with its full context]");
        return sb.toString();
    }

    static String asyncLaunchedNotificationXml(String taskId, String outputRef, String description, String subagentType, String model) {
        var outputRefXml = outputRef != null ? "<output-ref>" + outputRef + "</output-ref>\n" : "";
        var reminder = "  Async agent launched successfully.%n  agentId: %s (internal ID - do not mention to user)%n  The agent is working in the background. You will be notified automatically when it completes.%n  After it completes, pass task_id=%s to `task` to continue this agent with its full context.%n  Do not duplicate this agent's work — avoid working with the same files or topics it is using. Work on non-overlapping tasks, or briefly tell the user what you launched and end%n  your response.%n  output_file: %s%n  If asked, you can check progress before completion by using %s or %s tail on the output file.%n".formatted(taskId, taskId, outputRef, ReadFileTool.TOOL_NAME, ShellCommandTool.TOOL_NAME);
        return "<task-notification>%n<task-id>%s</task-id>%n<task-type>%s</task-type>%n<task-description>%s</task-description>%n<task-model>%s</task-model>%n<status>%s</status>%n%s%n<system-reminder>%s</system-reminder>%n</task-notification>%n".formatted(taskId, subagentType, description, model != null ? model : "default", "async_launched", outputRefXml, reminder);
    }

    private static String threadLine(SubagentThread thread) {
        var sb = new StringBuilder(128).append("- ").append(thread.taskId()).append(" (").append(thread.type()).append("): ");
        var description = oneLine(thread.description());
        if (!description.isEmpty()) {
            sb.append('"').append(description).append("\" — ");
        }
        sb.append(thread.runCount()).append(thread.runCount() == 1 ? " run" : " runs")
                .append(", last ").append(relativeTime(thread.lastActiveAtMs()))
                .append(", context≈").append(formatTokens(thread.contextTokens())).append(" tokens");
        if (thread.state() == SubagentThreadState.DORMANT) {
            sb.append(", dormant (reloaded when continued)");
        }
        return sb.toString();
    }

    private static String oneLine(String text) {
        if (text == null) return "";
        var single = text.replaceAll("[\\r\\n]+", " ").strip();
        return single.length() <= MAX_DESCRIPTION_LENGTH ? single : single.substring(0, MAX_DESCRIPTION_LENGTH - 3) + "...";
    }

    private static String relativeTime(long epochMs) {
        if (epochMs <= 0) return "just now";
        var delta = System.currentTimeMillis() - epochMs;
        if (delta < 60_000) return "just now";
        if (delta < 3_600_000) return delta / 60_000 + "m ago";
        if (delta < 86_400_000) return delta / 3_600_000 + "h ago";
        return delta / 86_400_000 + "d ago";
    }

    private static String formatTokens(long tokens) {
        if (tokens < 1000) return String.valueOf(tokens);
        return tokens / 1000 + "k";
    }

    private TaskToolPrompt() {
    }
}
