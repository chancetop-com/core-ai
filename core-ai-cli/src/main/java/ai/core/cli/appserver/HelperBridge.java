package ai.core.cli.appserver;

import ai.core.cli.helper.HelperConfigStore;
import ai.core.cli.helper.HelperEntry;
import ai.core.cli.helper.HelperException;
import ai.core.cli.helper.HelperService;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Helper management behind the desktop Hub panel: the user-level list of server agents mounted
 * as delegation tools, plus add / update / remove. Adding resolves the agent summary from the
 * hub so tool descriptions survive the server being unreachable.
 *
 * @author stephen
 */
public class HelperBridge {
    /** Present means "set" (a blank string clears the hint), absent means "leave unchanged". */
    private static String textOrNull(ObjectNode params, String name) {
        return params.hasNonNull(name) ? params.get(name).asText() : null;
    }

    private static ObjectNode helperResponse(HelperEntry entry) {
        var node = Params.object();
        node.set("helper", helperNode(entry));
        return node;
    }

    private static ObjectNode helperNode(HelperEntry entry) {
        var node = Params.object();
        node.put("agentId", entry.agentId);
        node.put("name", entry.name);
        node.put("description", entry.description);
        node.put("whenToUse", entry.whenToUse);
        node.put("enabled", HelperEntry.enabled(entry));
        node.put("addedAt", entry.addedAt);
        return node;
    }

    private static void addHelpers(ObjectNode node, List<HelperEntry> helpers) {
        var array = node.putArray("helpers");
        for (var entry : helpers) {
            array.add(helperNode(entry));
        }
    }

    private final HelperService service;

    public HelperBridge() {
        this(new HelperService(HelperConfigStore.defaultStore(), HelperService::clientOrNull));
    }

    HelperBridge(HelperService service) {
        this.service = service;
    }

    public ObjectNode list() {
        var node = Params.object();
        node.put("connected", service.connected());
        node.put("max", HelperConfigStore.MAX_HELPERS);
        addHelpers(node, service.list());
        return node;
    }

    public ObjectNode add(ObjectNode params) {
        try {
            return helperResponse(service.add(Params.requiredText(params, "agentId"), textOrNull(params, "whenToUse")));
        } catch (HelperException e) {
            throw RpcException.business(e.code(), e.getMessage(), e);
        }
    }

    public ObjectNode update(ObjectNode params) {
        try {
            var enabled = params.hasNonNull("enabled") ? params.get("enabled").asBoolean() : null;
            var entry = service.update(Params.requiredText(params, "agentId"), textOrNull(params, "whenToUse"), enabled);
            return helperResponse(entry);
        } catch (HelperException e) {
            throw RpcException.business(e.code(), e.getMessage(), e);
        }
    }

    public ObjectNode remove(ObjectNode params) {
        try {
            service.remove(Params.requiredText(params, "agentId"));
            var node = Params.object();
            node.put("removed", true);
            return node;
        } catch (HelperException e) {
            throw RpcException.business(e.code(), e.getMessage(), e);
        }
    }
}
