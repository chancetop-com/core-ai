package ai.core.cli.hub.agent;

import ai.core.api.server.agenthub.AgentHubDetail;
import ai.core.api.server.agenthub.AgentHubInputRequest;
import ai.core.api.server.agenthub.AgentHubRunResult;
import ai.core.api.server.agenthub.AgentHubSummary;
import ai.core.cli.hub.HubCliError;
import ai.core.cli.hub.HubExitCodes;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The run-result contract of {@code core-ai-cli agent run}: the status-to-exit-code mapping is the
 * scripted interface, stdout carries the answer only, the human metadata goes to stderr.
 *
 * @author stephen
 */
class RunResultRendererTest {
    private final RunResultRenderer renderer = new RunResultRenderer();

    @Test
    void statusMapsToStableExitCodes() {
        assertEquals(HubExitCodes.SUCCESS, renderer.exitCode("completed"));
        assertEquals(HubExitCodes.TOOL_ERROR, renderer.exitCode("failed"));
        assertEquals(HubExitCodes.TOOL_ERROR, renderer.exitCode("cancelled"));
        assertEquals(HubExitCodes.TIMEOUT, renderer.exitCode("running"));
        assertEquals(HubExitCodes.INPUT_REQUIRED, renderer.exitCode("input_required"));
        assertEquals(HubExitCodes.TOOL_ERROR, renderer.exitCode(null));
    }

    @Test
    void unknownStatusIsAnError() {
        assertThrows(HubCliError.class, () -> renderer.exitCode("exploded"));
    }

    @Test
    void renderKeepsStdoutForTheAnswerOnly() {
        var result = new AgentHubRunResult();
        result.status = "completed";
        result.output = "the answer";
        result.taskId = "task-1";
        result.contextId = "context-1";
        result.durationMs = 1200L;
        result.tokenUsage = Map.of("input", 10L, "output", 20L);

        var printed = render(result, 100);

        assertEquals("the answer" + System.lineSeparator(), printed.stdout);
        assertEquals(HubExitCodes.SUCCESS, printed.exitCode);
        assertTrue(printed.stderr.contains("status: completed, duration: 1200ms"), printed.stderr);
        assertTrue(printed.stderr.contains("task_id: task-1"), printed.stderr);
        assertTrue(printed.stderr.contains("context_id: context-1"), printed.stderr);
        assertTrue(printed.stderr.contains("tokens: input=10 output=20"), printed.stderr);
    }

    @Test
    void renderTruncatesLongOutputAndNotesIt() {
        var result = new AgentHubRunResult();
        result.status = "completed";
        result.output = "0123456789";
        result.outputTruncated = Boolean.TRUE;

        var printed = render(result, 5);

        assertTrue(printed.stdout.startsWith("01234\n... (output truncated"), printed.stdout);
        assertTrue(printed.stderr.contains("note: output was truncated"), printed.stderr);
    }

    @Test
    void renderInputRequiredPointsAtTheReplyCommand() {
        var result = new AgentHubRunResult();
        result.status = "input_required";
        result.taskId = "task-1";
        var input = new AgentHubInputRequest();
        input.callId = "call-1";
        input.tool = "shell";
        input.arguments = "{\"command\":\"ls\"}";
        input.message = "Tool requires approval: shell";
        result.inputRequest = input;

        var printed = render(result, 100);

        assertEquals(HubExitCodes.INPUT_REQUIRED, printed.exitCode);
        assertTrue(printed.stderr.contains("input required: Tool requires approval: shell"), printed.stderr);
        assertTrue(printed.stderr.contains("tool: shell"), printed.stderr);
        assertTrue(printed.stderr.contains("reply with: core-ai-cli agent reply task-1"), printed.stderr);
    }

    @Test
    void renderJsonPutsTheWholeResultOnStdout() {
        var result = new AgentHubRunResult();
        result.status = "completed";
        result.output = "the answer";

        var printed = render(result, 100, true);

        assertEquals(HubExitCodes.SUCCESS, printed.exitCode);
        assertTrue(printed.stdout.contains("\"status\""), printed.stdout);
        assertTrue(printed.stdout.contains("completed"), printed.stdout);
        assertEquals("", printed.stderr);
    }

    @Test
    void searchTextAlignsNameAndTypeAndMarksDefaultAndPublished() {
        var text = renderer.searchText(List.of(
                summary("code reviewer", "agent", Boolean.TRUE, null),
                summary("writer", "llm_call", null, ZonedDateTime.parse("2026-09-01T00:00:00Z"))));

        String prefix = "  " + "code reviewer" + " ".repeat(7) + "  " + "agent" + " ".repeat(4) + "  ";
        assertTrue(text.startsWith(prefix), text);
        assertTrue(text.contains("(default)"), text);
        assertTrue(text.contains("(published 2026-09-01)"), text);
    }

    @Test
    void searchTextWithoutAgentsSaysSo() {
        assertEquals("no agents found", renderer.searchText(List.of()));
        assertEquals("no agents found", renderer.searchText(null));
    }

    @Test
    void detailTextListsTheCapabilitySummaryOnly() {
        var detail = new AgentHubDetail();
        detail.id = "agent-1";
        detail.name = "code reviewer";
        detail.type = "agent";
        detail.status = "published";
        detail.source = "server";
        detail.description = "reviews code";
        detail.publishedAt = ZonedDateTime.parse("2026-09-01T00:00:00Z");
        detail.skillNames = List.of("jira");
        detail.subAgentNames = List.of("triage");
        detail.inputHint = "a pull request url";

        var text = renderer.detailText(detail);

        assertTrue(text.contains("code reviewer  [agent, published]"), text);
        assertTrue(text.contains("id: agent-1"), text);
        assertTrue(text.contains("published: 2026-09-01"), text);
        assertTrue(text.contains("skills: jira"), text);
        assertTrue(text.contains("sub agents: triage"), text);
        assertTrue(text.contains("input: a pull request url"), text);
    }

    @Test
    void detailTextOmitsEmptySections() {
        var detail = new AgentHubDetail();
        detail.id = "agent-1";
        detail.name = "writer";
        detail.type = "llm_call";
        detail.status = "draft";
        detail.source = "server";

        var text = renderer.detailText(detail);

        assertTrue(text.contains("writer  [llm_call, draft]"), text);
        assertFalse(text.contains("skills:"), text);
        assertFalse(text.contains("description:"), text);
    }

    private AgentHubSummary summary(String name, String type, Boolean systemDefault, ZonedDateTime publishedAt) {
        var summary = new AgentHubSummary();
        summary.id = "agent-1";
        summary.name = name;
        summary.type = type;
        summary.status = "published";
        summary.description = "does things";
        summary.systemDefault = systemDefault;
        summary.publishedAt = publishedAt;
        return summary;
    }

    private Printed render(AgentHubRunResult result, int maxOutput) {
        return render(result, maxOutput, false);
    }

    private Printed render(AgentHubRunResult result, int maxOutput, boolean json) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var originalOut = System.out;
        var originalErr = System.err;
        int exitCode;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            exitCode = renderer.render(result, maxOutput, json);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return new Printed(exitCode, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private record Printed(int exitCode, String stdout, String stderr) {
    }
}
