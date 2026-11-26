package ai.core.server.session;

import ai.core.server.sandbox.SandboxService;
import ai.core.tool.subagent.SubagentOutputSink;
import ai.core.tool.subagent.SubagentOutputSinkFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/**
 * Writes the output of a server-side background task (subagent or non-intercepted bash) into the
 * session's sandbox. The reference is a path the agent can read with the file tools; writes are
 * best-effort so a missing sandbox never turns a completed task into a failed one.
 *
 * @author stephen
 */
class SandboxSubagentOutputSinkFactory implements SubagentOutputSinkFactory {
    private static final Logger LOGGER = LoggerFactory.getLogger(SandboxSubagentOutputSinkFactory.class);
    static final String TASK_DIR = "/workspace/.core-ai/tasks";

    /** The deployment-level factory for one session; null keeps the tools on their synchronous path. */
    static SubagentOutputSinkFactory of(SandboxService sandboxes, String sessionId) {
        if (!sandboxes.backgroundTasksEnabled) return null;
        return new SandboxSubagentOutputSinkFactory(sandboxes, sessionId, sandboxes.backgroundOutputMaxBytes);
    }

    private final SandboxService sandboxes;
    private final String sessionId;
    private final long maxBytes;

    SandboxSubagentOutputSinkFactory(SandboxService sandboxes, String sessionId, long maxBytes) {
        this.sandboxes = sandboxes;
        this.sessionId = sessionId;
        this.maxBytes = maxBytes > 0 ? maxBytes : 1024 * 1024;
    }

    @Override
    public SubagentOutputSink create(String taskId) {
        return new Sink(TASK_DIR + "/" + taskId + ".out", maxBytes);
    }

    private final class Sink implements SubagentOutputSink {
        private final String path;
        private final long limit;
        private final StringBuilder buffer = new StringBuilder(4096);
        private boolean closed;

        Sink(String path, long limit) {
            this.path = path;
            this.limit = limit;
        }

        @Override
        public void write(String content) {
            if (content == null || closed) return;
            long room = limit - buffer.length();
            if (room <= 0) return;
            if (content.length() <= room) {
                buffer.append(content);
                return;
            }
            buffer.append(content, 0, (int) room).append("\n[output truncated at ").append(limit).append(" bytes]");
        }

        @Override
        public String getReference() {
            return path;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            var sandbox = sandboxes.getSandbox(sessionId);
            if (sandbox == null) {
                LOGGER.warn("background task output is lost: no sandbox for session={}, path={}", sessionId, path);
                return;
            }
            try {
                sandbox.uploadFile(path, buffer.toString().getBytes(StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                LOGGER.warn("failed to write background task output, task result stays in the notification: session={}, path={}",
                        sessionId, path, e);
            }
        }
    }
}
