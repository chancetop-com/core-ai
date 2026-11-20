package ai.core.cli.appserver;

import ai.core.agent.AttachedContent;
import ai.core.session.InProcessAgentSession;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Root admission and the lifecycle methods that operate on one session. Kept out of the registry so
 * session listing/creation and the Task Run surface remain separate concerns.
 *
 * @author stephen
 */
final class SessionRunApi {
    private final EngineBootstrap bootstrap;
    private final EngineSessionRegistry registry;

    SessionRunApi(EngineBootstrap bootstrap, EngineSessionRegistry registry) {
        this.bootstrap = bootstrap;
        this.registry = registry;
    }

    ObjectNode send(ObjectNode params) {
        bootstrap.requireTrusted("sending a message");
        registry.intake.requireIdle("sending a message");
        var session = registry.requireSession(params);
        var parsed = SendParts.parse(params, bootstrap.workspace, SessionLifecycle.stagingDir(session.id()));
        if (!parsed.hasContent()) {
            throw RpcException.invalidParams("message must contain text or attachments");
        }
        var requestId = Params.optionalText(params, "requestId");
        if (requestId == null) requestId = "request-" + UUID.randomUUID();
        var turnId = Params.optionalText(params, "turnId");
        var taskRuns = session.inProcess().taskRuns();
        var before = taskRuns.activeSnapshot();
        var opened = before == null || before.terminal();
        if (opened) taskRuns.openRoot(requestId);
        boolean wasRunning = session.running();
        List<AttachedContent> attachments = parsed.attachments().isEmpty() ? null : parsed.attachments();
        var disposition = session.inProcess().sendTrackedMessage(parsed.text(), null, attachments, turnId);
        if (disposition == InProcessAgentSession.SendDisposition.QUEUED) {
            taskRuns.requestQueued();
        } else if (disposition == InProcessAgentSession.SendDisposition.STEERED && opened) {
            taskRuns.interrupt("steered_into_existing_execution");
        } else if (disposition == InProcessAgentSession.SendDisposition.REJECTED && opened) {
            taskRuns.admissionFailed("session_queue_full");
        }
        var node = Params.object();
        node.put("accepted", disposition != InProcessAgentSession.SendDisposition.REJECTED);
        node.put("queued", wasRunning);
        node.put("requestId", requestId);
        node.put("admission", switch (disposition) {
            case STEERED -> "steered";
            case QUEUED -> wasRunning ? "queued" : "active";
            case REJECTED -> "rejected";
        });
        var current = taskRuns.activeSnapshot();
        if (current != null) {
            var ref = node.putObject("taskRun");
            ref.put("taskRunId", current.taskRunId());
            ref.put("runGeneration", current.runGeneration());
            ref.put("state", current.state().name().toLowerCase(Locale.ROOT));
            ref.put("coverage", current.coverage());
        }
        return node;
    }

    ObjectNode cancel(ObjectNode params) {
        var session = registry.requireSession(params);
        session.inProcess().cancelTurn();
        session.inProcess().cancelBackgroundTasks();
        session.inProcess().taskRuns().cancelRun("user_cancelled");
        var node = Params.object();
        node.put("sessionId", session.id());
        node.put("cancelRequested", true);
        var snapshot = session.inProcess().taskRuns().activeSnapshot();
        if (snapshot != null) {
            node.put("taskRunId", snapshot.taskRunId());
            node.put("runGeneration", snapshot.runGeneration());
            node.put("state", snapshot.state().name().toLowerCase(Locale.ROOT));
        }
        return node;
    }

    ObjectNode inspect(ObjectNode params) {
        return registry.requireSession(params).lifecycle().inspect();
    }

    ObjectNode bind(ObjectNode params) {
        var session = registry.requireSession(params);
        return session.lifecycle().bind(Params.requiredText(params, "watchId"),
                Params.requiredText(params, "taskRunId"),
                params.path("runGeneration").asLong(0),
                params.path("ttlMillis").asLong(0));
    }

    ObjectNode bindStatus(ObjectNode params) {
        return registry.requireSession(params).lifecycle().bindStatus(Params.requiredText(params, "watchId"));
    }

    ObjectNode replay(ObjectNode params) {
        return registry.requireSession(params).lifecycle().replay(Params.requiredText(params, "watchId"),
                params.path("afterSeq").asLong(0), Params.intParam(params, "limit", 200));
    }

    ObjectNode unbind(ObjectNode params) {
        return registry.requireSession(params).lifecycle().unbind(Params.requiredText(params, "watchId"));
    }
}
