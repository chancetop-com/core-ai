package ai.core.cli.hub.agent;

import ai.core.api.server.agenthub.AgentHubDetail;
import ai.core.api.server.agenthub.AgentHubInputRequest;
import ai.core.api.server.agenthub.AgentHubRunResult;
import ai.core.api.server.agenthub.AgentHubSummary;
import ai.core.cli.ConsoleWriter;
import ai.core.cli.hub.HubCliError;
import ai.core.cli.hub.HubExitCodes;
import ai.core.cli.hub.HubRenderer;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Rendering for {@code core-ai-cli agent ...}: a compact catalog table for search/show and a
 * run-oriented result renderer that keeps the machine contract (JSON) separate from the human
 * view.
 * <p>
 * A run result always prints the agent's answer to stdout; everything a human needs to decide
 * what to do next (status, ids, the pending approval, usage) goes to stderr, so
 * {@code core-ai-cli agent run ... | pbcopy} yields just the answer.
 *
 * @author stephen
 */
public class RunResultRenderer {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public String searchText(List<AgentHubSummary> agents) {
        if (agents == null || agents.isEmpty()) return "no agents found";
        int nameWidth = 20;
        for (var agent : agents) {
            nameWidth = Math.max(nameWidth, length(agent.name));
        }
        var text = new StringBuilder();
        for (var agent : agents) {
            text.append("  ").append(pad(agent.name, nameWidth)).append("  ")
                    .append(pad(agent.type, 9)).append("  ")
                    .append(oneLine(agent.description, 70));
            String suffix = suffix(agent);
            if (!suffix.isEmpty()) text.append("  (").append(suffix).append(')');
            text.append('\n');
        }
        return text.toString();
    }

    public String detailText(AgentHubDetail detail) {
        var text = new StringBuilder(512);
        text.append(detail.name).append("  [").append(detail.type).append(", ").append(detail.status).append("]\nid: ")
                .append(detail.id).append('\n');
        if (detail.description != null && !detail.description.isBlank()) {
            text.append("description: ").append(detail.description).append('\n');
        }
        text.append("source: ").append(detail.source).append('\n');
        if (detail.publishedAt != null) text.append("published: ").append(DATE.format(detail.publishedAt)).append('\n');
        if (detail.subAgentNames != null && !detail.subAgentNames.isEmpty()) {
            text.append("sub agents: ").append(String.join(", ", detail.subAgentNames)).append('\n');
        }
        if (detail.skillNames != null && !detail.skillNames.isEmpty()) {
            text.append("skills: ").append(String.join(", ", detail.skillNames)).append('\n');
        }
        if (detail.inputHint != null && !detail.inputHint.isBlank()) {
            text.append("input: ").append(oneLine(detail.inputHint, 200)).append('\n');
        }
        return text.toString();
    }

    /** Prints a run result (stdout = answer, stderr = metadata) and returns the matching exit code. */
    public int render(AgentHubRunResult result, int maxOutput, boolean json) {
        if (json) {
            HubRenderer.printJson(result);
        } else {
            printOutput(result, maxOutput);
            printMetadata(result);
        }
        return exitCode(result.status);
    }

    public int exitCode(String status) {
        if (status == null) return HubExitCodes.TOOL_ERROR;
        return switch (status) {
            case "completed" -> HubExitCodes.SUCCESS;
            case "cancelled", "failed" -> HubExitCodes.TOOL_ERROR;
            case "input_required" -> HubExitCodes.INPUT_REQUIRED;
            case "running" -> HubExitCodes.TIMEOUT;
            default -> throw new HubCliError(HubExitCodes.TOOL_ERROR, "unexpected run status: " + status);
        };
    }

    private void printOutput(AgentHubRunResult result, int maxOutput) {
        String output = result.output;
        if (output == null || output.isEmpty()) return;
        String body = output.length() > maxOutput
                ? output.substring(0, maxOutput) + "\n... (output truncated; raise --max-output for more)"
                : output;
        ConsoleWriter.print(body);
        if (!body.endsWith("\n")) ConsoleWriter.println();
    }

    private void printMetadata(AgentHubRunResult result) {
        ConsoleWriter.printError("status: " + result.status
                + (result.durationMs == null ? "" : ", duration: " + result.durationMs + "ms"));
        if (result.taskId != null) ConsoleWriter.printError("task_id: " + result.taskId);
        if (result.contextId != null) ConsoleWriter.printError("context_id: " + result.contextId);
        if (result.tokenUsage != null && !result.tokenUsage.isEmpty()) {
            ConsoleWriter.printError("tokens: " + usage(result.tokenUsage));
        }
        if (result.errorMessage != null) ConsoleWriter.printError("error: " + result.errorMessage);
        if (Boolean.TRUE.equals(result.outputTruncated)) ConsoleWriter.printError("note: output was truncated");
        printInputRequest(result);
    }

    private void printInputRequest(AgentHubRunResult result) {
        AgentHubInputRequest input = result.inputRequest;
        if (input == null) return;
        ConsoleWriter.printError("input required: " + (input.message == null ? "" : input.message));
        if (input.tool != null) ConsoleWriter.printError("  tool: " + input.tool);
        if (input.arguments != null) ConsoleWriter.printError("  arguments: " + oneLine(input.arguments, 500));
        ConsoleWriter.printError("reply with: core-ai-cli agent reply " + result.taskId
                + " --approve|--deny|--message \"...\"");
    }

    private String usage(Map<String, Long> tokenUsage) {
        var text = new StringBuilder();
        Long input = tokenUsage.get("input");
        Long output = tokenUsage.get("output");
        if (input != null) text.append("input=").append(input);
        if (output != null) {
            if (!text.isEmpty()) text.append(' ');
            text.append("output=").append(output);
        }
        return text.toString();
    }

    private String suffix(AgentHubSummary agent) {
        if (Boolean.TRUE.equals(agent.systemDefault)) return "default";
        if (agent.publishedAt != null) return "published " + DATE.format(agent.publishedAt);
        return agent.status == null ? "" : agent.status;
    }

    private String oneLine(String value, int max) {
        if (value == null) return "";
        String text = value.replace('\n', ' ').replace('\r', ' ').trim();
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    private String pad(String value, int width) {
        String text = value == null ? "" : value;
        if (text.length() >= width) return text;
        return text + " ".repeat(width - text.length());
    }

    private int length(String value) {
        return value == null ? 0 : value.length();
    }
}
