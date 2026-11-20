package ai.core.cli.appserver;

import ai.core.session.lifecycle.TaskRunState;

/**
 * Resolves a target session in this engine's workspace and queues the forwarded message as its next
 * user turn. Resume errors (not found, corrupt, newer version, locked by another process) propagate
 * as {@link RpcException} so the tool can report the business code.
 *
 * @author stephen
 */
final class EngineSessionMessageForwarder implements SendSessionMessageTool.Forwarder {
    private final EngineSessionRegistry registry;

    EngineSessionMessageForwarder(EngineSessionRegistry registry) {
        this.registry = registry;
    }

    @Override
    public SendSessionMessageTool.Forwarded forward(String sessionId, String message, String turnId) {
        var params = Params.object();
        params.put("sessionId", sessionId);
        var resumed = registry.resume(params);
        var target = registry.find(sessionId);
        if (target == null) {
            throw RpcException.business("SESSION_NOT_FOUND", "session not found: " + sessionId, "sessionId", sessionId);
        }
        boolean queued = target.running() || target.inProcess().hasPendingWork();
        var taskRuns = target.inProcess().taskRuns();
        var tracked = taskRuns.openRoot(turnId, turnId);
        if (!target.inProcess().enqueueMessage(message, null, null, turnId)) {
            if (tracked.state() == TaskRunState.QUEUED) taskRuns.discardQueued(turnId, "session_queue_full");
            else taskRuns.admissionFailed("session_queue_full");
            throw RpcException.business("SESSION_QUEUE_FULL", "session queue is full: " + sessionId, "sessionId", sessionId);
        }
        if (tracked.state() != TaskRunState.QUEUED) taskRuns.requestQueued(turnId);
        return new SendSessionMessageTool.Forwarded(sessionId,
                resumed.path("status").asText("idle"), queued, turnId);
    }
}
