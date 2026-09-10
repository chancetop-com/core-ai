package ai.core.cli.hub.agent;

import ai.core.api.server.agenthub.AgentHubAttachment;
import ai.core.api.server.agenthub.AgentHubRunRequest;
import ai.core.cli.hub.HubCliError;
import ai.core.cli.hub.HubCommandBase;
import ai.core.cli.hub.HubExitCodes;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs one agent as a task and blocks until it finishes, needs input, or the wait budget runs out.
 * <p>
 * The result is one of three outcomes the caller has to tell apart (see {@link HubExitCodes}):
 * {@code completed} (0), {@code input_required} (7 — answer with {@code agent reply}), or
 * {@code running} (6 — the task outlived the wait, poll with {@code agent status}).
 *
 * @author stephen
 */
@Command(name = "run", description = "Run an agent with a task and wait for its result")
class AgentRunCommand extends HubCommandBase {
    private static final int DEFAULT_MAX_OUTPUT = 20000;

    @Parameters(index = "0", paramLabel = "id|name", description = "Agent id, or a name unique in your catalog")
    String idOrName;

    @Option(names = "--task", description = "The task to send: self-contained, with all context the agent needs")
    String task;

    @Option(names = "--task-file", description = "Read the task from a file ('-' for stdin); joined after --task")
    Path taskFile;

    @Option(names = "--context-id", description = "Continue an earlier conversation (context_id of a previous run)")
    String contextId;

    @Option(names = "--attach", description = "Attachment URL (repeatable); the agent fetches it itself")
    List<String> attachments;

    @Option(names = "--timeout", description = "Seconds to wait for the result (default 120, max 300)")
    Integer timeoutSeconds;

    @Option(names = "--detach", description = "Submit and return immediately with status=running")
    boolean detach;

    @Option(names = "--max-output", description = "Truncate printed output after N chars (default 20000)")
    Integer maxOutput;

    @Override
    protected Integer execute() {
        var client = agentClient();
        var request = new AgentHubRunRequest();
        request.task = composeTask();
        request.contextId = contextId;
        request.attachments = attachments();
        request.timeoutSeconds = timeoutSeconds;
        request.detach = detach ? Boolean.TRUE : null;
        request.maxOutputChars = outputLimit();

        var agentId = new AgentNameResolver(client).resolve(idOrName);
        metadata("running " + agentId + " ...");
        var result = client.run(agentId, request);
        return new RunResultRenderer().render(result, outputLimit(), json());
    }

    private String composeTask() {
        String fromFile = readTaskFile();
        boolean hasTask = task != null && !task.isBlank();
        boolean hasFile = fromFile != null && !fromFile.isBlank();
        if (!hasTask && !hasFile) throw new HubCliError(HubExitCodes.USAGE, "task required: pass --task or --task-file");
        if (hasTask && hasFile) return task + "\n\n" + fromFile;
        return hasTask ? task : fromFile;
    }

    private String readTaskFile() {
        if (taskFile == null) return null;
        try {
            if ("-".equals(taskFile.toString())) {
                return new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
            }
            return Files.readString(taskFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new HubCliError(HubExitCodes.USAGE, "cannot read --task-file: " + e.getMessage(), e);
        }
    }

    private List<AgentHubAttachment> attachments() {
        if (attachments == null || attachments.isEmpty()) return null;
        var result = new ArrayList<AgentHubAttachment>();
        for (var url : attachments) {
            if (url == null || url.isBlank()) continue;
            var attachment = new AgentHubAttachment();
            attachment.url = url;
            result.add(attachment);
        }
        return result.isEmpty() ? null : result;
    }

    private int outputLimit() {
        return maxOutput == null || maxOutput <= 0 ? DEFAULT_MAX_OUTPUT : maxOutput;
    }
}
