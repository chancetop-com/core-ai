package ai.core.server.sandboxhub;

import ai.core.api.server.mcphub.HubCallRequest;
import ai.core.server.messaging.SessionCommand;
import ai.core.utils.JsonUtil;

import java.util.Map;

/**
 * Sandbox hub commands arriving over the session RPC channel: the pod that owns the session serves
 * them, because the catalog and the tool calls are built from that pod's session state. Results are
 * JSON, as the requester may run a different build of the server.
 *
 * @author stephen
 */
public class SandboxHubCommands {
    private final SandboxHubService hubService;

    public SandboxHubCommands(SandboxHubService hubService) {
        this.hubService = hubService;
    }

    public String catalog(SessionCommand command) {
        return JsonUtil.toJson(hubService.snapshot(session(command, null)));
    }

    public String call(SessionCommand command) {
        var payload = JsonUtil.fromJson(SandboxHubCallCommandPayload.class, command.payload());
        var request = new HubCallRequest();
        request.arguments = payload.arguments;
        request.timeoutSeconds = payload.timeoutSeconds;
        return JsonUtil.toJson(hubService.call(session(command, payload.sandboxId), payload.name, request));
    }

    public String poll(SessionCommand command) {
        var payload = JsonUtil.fromJson(Map.class, command.payload());
        var taskId = (String) payload.get("taskId");
        return JsonUtil.toJson(hubService.task(session(command, null), taskId));
    }

    // The sandbox identity (sandbox id, token expiry) belongs to the caller that holds the token; the
    // replica that owns the session only needs it for the audit row of the call.
    private SandboxHubService.SandboxHubSession session(SessionCommand command, String sandboxId) {
        return new SandboxHubService.SandboxHubSession(command.sessionId(), command.userId(), sandboxId, null);
    }
}
