package ai.core.cli.helper;

import ai.core.agent.AttachedContent;
import ai.core.agent.ExecutionContext;
import ai.core.api.server.agenthub.AgentHubAttachment;
import ai.core.api.server.agenthub.AgentHubInputRequest;
import ai.core.api.server.agenthub.AgentHubReplyRequest;
import ai.core.api.server.agenthub.AgentHubRunRequest;
import ai.core.api.server.agenthub.AgentHubRunResult;
import ai.core.cli.hub.agent.AgentHubClient;
import ai.core.cli.hub.agent.LocalAttachmentUploader;
import ai.core.cli.http.RemoteApiException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Thread state machine of a helper tool: first run, follow-up continuation, input_required
 * relay, running-task polling, error reset and validation failures.
 *
 * @author stephen
 */
class HelperAgentToolCallTest {
    @Test
    void firstCallRunsNewTaskAndFollowUpContinuesSameContext() {
        var client = new FakeHubClient();
        client.runResults.add(result("completed", "t-1", "ctx-1", "annual leave is 15 days"));
        client.runResults.add(result("completed", "t-2", "ctx-1", "sick leave needs a certificate"));
        var tool = tool(client);

        var first = tool.execute("{\"task\":\"how many annual leave days?\"}", context());
        assertEquals("annual leave is 15 days", first.getResult());
        assertNull(client.runRequests.getFirst().contextId);
        assertEquals("t-1", first.getStats().get("helper_task_id"));

        var second = tool.execute("{\"task\":\"and sick leave?\"}", context());
        assertEquals("sick leave needs a certificate", second.getResult());
        assertEquals("ctx-1", client.runRequests.get(1).contextId);
    }

    @Test
    void inputRequiredIsRelayedAndAnsweredThroughReply() {
        var client = new FakeHubClient();
        var waiting = result("input_required", "t-1", "ctx-1", null);
        waiting.inputRequest = new AgentHubInputRequest();
        waiting.inputRequest.callId = "call-1";
        waiting.inputRequest.tool = "shell.exec";
        waiting.inputRequest.arguments = "{\"command\":\"send email\"}";
        client.runResults.add(waiting);
        client.replyResults.add(result("completed", "t-2", "ctx-1", "email sent"));
        var tool = tool(client);

        var pending = tool.execute("{\"task\":\"send the offer letter\"}", context());
        assertTrue(pending.isCompleted());
        assertTrue(pending.getResult().contains("needs your confirmation"));
        assertTrue(pending.getResult().contains("shell.exec"));

        var done = tool.execute("{\"task\":\"approve\"}", context());
        assertEquals("email sent", done.getResult());
        assertEquals(1, client.replyRequests.size());
        assertEquals("approve", client.replyRequests.getFirst().message);
        assertEquals(1, client.runRequests.size());
    }

    @Test
    void runningTaskIsPolledBeforeAnythingNewIsSent() {
        var client = new FakeHubClient();
        client.runResults.add(result("running", "t-1", "ctx-1", "partial"));
        client.statusResults.add(result("running", "t-1", "ctx-1", "partial more"));
        client.statusResults.add(result("completed", "t-1", "ctx-1", "final output"));
        client.runResults.add(result("completed", "t-2", "ctx-1", "second answer"));
        var tool = tool(client);

        tool.execute("{\"task\":\"long task\"}", context());
        var still = tool.execute("{\"task\":\"is it done?\"}", context());
        assertTrue(still.getResult().contains("still working"));
        assertEquals(1, client.runRequests.size());

        var finished = tool.execute("{\"task\":\"is it done now?\"}", context());
        assertTrue(finished.getResult().contains("has finished"));
        assertTrue(finished.getResult().contains("final output"));
        assertEquals(1, client.runRequests.size());

        var second = tool.execute("{\"task\":\"next question\"}", context());
        assertEquals("second answer", second.getResult());
        assertEquals("ctx-1", client.runRequests.get(1).contextId);
    }

    @Test
    void notFoundErrorResetsTheThread() {
        var client = new FakeHubClient();
        client.runError = new RemoteApiException(404, "agent not found");
        var tool = tool(client);

        var failed = tool.execute("{\"task\":\"hello\"}", context());
        assertTrue(failed.isFailed());
        assertTrue(failed.getResult().contains("no longer available"));

        client.runError = null;
        client.runResults.add(result("completed", "t-2", "ctx-2", "fresh start"));
        var retried = tool.execute("{\"task\":\"hello again\"}", context());
        assertEquals("fresh start", retried.getResult());
        assertNull(client.runRequests.get(1).contextId);
    }

    @Test
    void missingLoginFailsWithHint() {
        var tool = tool(null);

        var result = tool.execute("{\"task\":\"hello\"}", context());

        assertTrue(result.isFailed());
        assertTrue(result.getResult().contains("signed in"));
    }

    @Test
    void blankAndTooLongTaskAreRejected() {
        var client = new FakeHubClient();
        var tool = tool(client);

        assertTrue(tool.execute("{\"task\":\"  \"}", context()).isFailed());
        assertTrue(tool.execute("{\"task\":\"" + "a".repeat(30_001) + "\"}", context()).isFailed());
        assertTrue(client.runRequests.isEmpty());
    }

    @Test
    void helperIsTaggedAsAgentForToolEvents() {
        var tool = tool(new FakeHubClient());

        assertEquals("agent", tool.getSourceType());
    }

    @Test
    void messageAttachmentsRideAlongWithTheTask() {
        var client = new FakeHubClient();
        client.runResults.add(result("completed", "t-1", "ctx-1", "evaluated"));
        var uploader = new FakeUploader();
        var tool = tool(client, uploader);

        var first = tool.execute("{\"task\":\"evaluate this resume\"}", contextWithPdfAttachment());

        assertEquals("evaluated", first.getResult());
        assertEquals(1, client.runRequests.getFirst().attachments.size());
        var attachment = client.runRequests.getFirst().attachments.getFirst();
        assertEquals("resume.pdf", attachment.fileName);
        assertEquals("application/pdf", attachment.contentType);
        assertEquals("pdf", attachment.type);
        assertEquals("https://cdn/ai/uploads/resume.pdf", attachment.url);
        assertEquals(1, uploader.uploads);
        assertEquals(1, first.getStats().get("helper_attachments"));
    }

    @Test
    void attachmentIsDeliveredOnlyOnceOnTheThread() {
        var client = new FakeHubClient();
        client.runResults.add(result("completed", "t-1", "ctx-1", "first"));
        client.runResults.add(result("completed", "t-2", "ctx-1", "second"));
        var uploader = new FakeUploader();
        var tool = tool(client, uploader);

        tool.execute("{\"task\":\"evaluate this resume\"}", contextWithPdfAttachment());
        var second = tool.execute("{\"task\":\"also check the dates\"}", contextWithPdfAttachment());

        assertEquals("second", second.getResult());
        assertNull(client.runRequests.get(1).attachments);
        assertEquals(1, uploader.uploads);
    }

    @Test
    void inputRequiredReplyDoesNotResendAttachments() {
        var client = new FakeHubClient();
        var waiting = result("input_required", "t-1", "ctx-1", null);
        waiting.inputRequest = new AgentHubInputRequest();
        waiting.inputRequest.tool = "shell.exec";
        client.runResults.add(waiting);
        client.replyResults.add(result("completed", "t-2", "ctx-1", "sent"));
        var uploader = new FakeUploader();
        var tool = tool(client, uploader);

        tool.execute("{\"task\":\"send the offer letter\"}", contextWithPdfAttachment());
        var done = tool.execute("{\"task\":\"approve\"}", contextWithPdfAttachment());

        assertEquals("sent", done.getResult());
        assertEquals(1, client.runRequests.size());
        assertEquals(1, client.runRequests.getFirst().attachments.size());
        assertEquals(1, uploader.uploads);
    }

    @Test
    void uploadFailureFailsTheDelegationAndRetryDeliversIt() {
        var client = new FakeHubClient();
        var uploader = new FakeUploader();
        uploader.error = new IllegalStateException("blob store is down");
        var tool = tool(client, uploader);

        var failed = tool.execute("{\"task\":\"evaluate this resume\"}", contextWithPdfAttachment());

        assertTrue(failed.isFailed());
        assertTrue(failed.getResult().contains("Failed to deliver message attachments"));
        assertTrue(client.runRequests.isEmpty());

        uploader.error = null;
        client.runResults.add(result("completed", "t-1", "ctx-1", "ok"));
        var retried = tool.execute("{\"task\":\"evaluate this resume\"}", contextWithPdfAttachment());

        assertEquals("ok", retried.getResult());
        assertEquals(1, client.runRequests.getFirst().attachments.size());
    }

    @Test
    void urlAttachmentIsForwardedAsIsWithoutUploading() {
        var client = new FakeHubClient();
        client.runResults.add(result("completed", "t-1", "ctx-1", "seen"));
        var uploader = new FakeUploader();
        var tool = tool(client, uploader);
        var context = ExecutionContext.builder().sessionId("s-1").build();
        context.setAttachedContents(List.of(AttachedContent.ofUrl(
                "https://cdn/photo.png", AttachedContent.AttachedContentType.IMAGE)));

        tool.execute("{\"task\":\"look at this\"}", context);

        assertEquals(0, uploader.uploads);
        var attachment = client.runRequests.getFirst().attachments.getFirst();
        assertEquals("https://cdn/photo.png", attachment.url);
        assertEquals("image", attachment.type);
    }

    @Test
    void aPlainMessageKeepsTheRunRequestLean() {
        var client = new FakeHubClient();
        client.runResults.add(result("completed", "t-1", "ctx-1", "ok"));
        var tool = tool(client);

        tool.execute("{\"task\":\"hello\"}", context());

        assertNull(client.runRequests.getFirst().attachments);
    }

    @Test
    void descriptionTellsTheModelFilesRideAlong() {
        var tool = tool(new FakeHubClient());

        assertTrue(tool.getDescription().contains("attached to the user's current message"));
    }

    private HelperAgentToolCall tool(FakeHubClient client) {
        return tool(client, new FakeUploader());
    }

    private HelperAgentToolCall tool(FakeHubClient client, FakeUploader uploader) {
        var entry = new HelperEntry();
        entry.agentId = "a1";
        entry.name = "HR 助手";
        entry.enabled = Boolean.TRUE;
        return HelperAgentToolCall.builder()
                .helper(entry)
                .name("hr")
                .clientSupplier(() -> client)
                .uploaderSupplier(() -> uploader)
                .build();
    }

    private ExecutionContext context() {
        return ExecutionContext.builder().sessionId("s-1").build();
    }

    private ExecutionContext contextWithPdfAttachment() {
        var context = ExecutionContext.builder().sessionId("s-1").build();
        context.setAttachedContents(List.of(AttachedContent.ofBase64(
                Base64.getEncoder().encodeToString("%PDF-1.4 resume".getBytes(StandardCharsets.UTF_8)),
                "application/pdf", AttachedContent.AttachedContentType.PDF, "resume.pdf")));
        return context;
    }

    private AgentHubRunResult result(String status, String taskId, String contextId, String output) {
        var result = new AgentHubRunResult();
        result.status = status;
        result.taskId = taskId;
        result.contextId = contextId;
        result.output = output;
        return result;
    }

    private static final class FakeUploader extends LocalAttachmentUploader {
        private int uploads;
        private RuntimeException error;

        FakeUploader() {
            super("https://server.example", "test-key", false);
        }

        @Override
        public AgentHubAttachment upload(byte[] data, String fileName, String contentType) {
            if (error != null) {
                throw error;
            }
            uploads++;
            var attachment = new AgentHubAttachment();
            attachment.url = "https://cdn/ai/uploads/" + fileName;
            attachment.type = "application/pdf".equals(contentType) ? "pdf" : "file";
            attachment.fileName = fileName;
            attachment.contentType = contentType;
            attachment.container = "static";
            attachment.blobName = "ai/uploads/" + fileName;
            return attachment;
        }
    }

    private static final class FakeHubClient extends AgentHubClient {
        private final Deque<AgentHubRunResult> runResults = new ArrayDeque<>();
        private final Deque<AgentHubRunResult> statusResults = new ArrayDeque<>();
        private final Deque<AgentHubRunResult> replyResults = new ArrayDeque<>();
        private final List<AgentHubRunRequest> runRequests = new ArrayList<>();
        private final List<AgentHubReplyRequest> replyRequests = new ArrayList<>();
        private RemoteApiException runError;

        FakeHubClient() {
            super("https://server.example", "test-key", false);
        }

        @Override
        public AgentHubRunResult run(String id, AgentHubRunRequest request) {
            runRequests.add(request);
            if (runError != null) {
                throw runError;
            }
            return runResults.removeFirst();
        }

        @Override
        public AgentHubRunResult status(String taskId) {
            return statusResults.removeFirst();
        }

        @Override
        public AgentHubRunResult reply(String taskId, AgentHubReplyRequest request) {
            replyRequests.add(request);
            return replyResults.removeFirst();
        }
    }
}
