package ai.core.cli.hub.agent;

import ai.core.api.server.agenthub.AgentHubSummary;
import ai.core.cli.hub.HubCliError;
import ai.core.cli.hub.HubExitCodes;

import java.util.List;

/**
 * Resolves the {@code <id | name>} argument of the {@code agent} subcommands.
 * <p>
 * Ids are the primary key and are passed through untouched; anything else is looked up by name in
 * the caller's visible catalog. A name that matches several agents is a usage error listing the
 * candidates — the caller has to disambiguate by id (agent names are only unique per owner).
 *
 * @author stephen
 */
public class AgentNameResolver {
    private static final int ID_LENGTH = 24;

    private final AgentHubClient client;

    public AgentNameResolver(AgentHubClient client) {
        this.client = client;
    }

    public String resolve(String idOrName) {
        if (looksLikeId(idOrName)) return idOrName;
        var candidates = client.lookup(idOrName).candidates;
        if (candidates == null || candidates.isEmpty()) {
            throw new HubCliError(HubExitCodes.NOT_FOUND, "agent not found, name=" + idOrName);
        }
        if (candidates.size() > 1) {
            throw new HubCliError(HubExitCodes.USAGE, ambiguousMessage(idOrName, candidates));
        }
        return candidates.get(0).id;
    }

    private String ambiguousMessage(String name, List<AgentHubSummary> candidates) {
        var message = new StringBuilder(256).append("more than one agent is named '").append(name).append("', use an id:\n");
        for (var candidate : candidates) {
            message.append("  ").append(candidate.id).append("  ").append(candidate.name);
            if (candidate.ownerIsMe != null && candidate.ownerIsMe) message.append("  (mine)");
            message.append('\n');
        }
        return message.toString();
    }

    private boolean looksLikeId(String value) {
        if (value == null || value.length() != ID_LENGTH) return false;
        for (int index = 0; index < value.length(); index++) {
            if (Character.digit(value.charAt(index), 16) < 0) return false;
        }
        return true;
    }
}
