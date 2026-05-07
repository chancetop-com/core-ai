package ai.core.cli.remote;

/**
 * One remote agent exposed to the local CLI agent for search and delegation.
 *
 * @author xander
 */
public record RemoteAgentCatalogEntry(String id, String serverId, String agentId, String name, String description,
                                      String status, A2ARemoteAgentConfig config) {
    public String searchableText() {
        return text(id) + " " + text(serverId) + " " + text(agentId) + " " + text(name) + " " + text(description)
                + " " + text(status);
    }

    private String text(String value) {
        return value == null ? "" : value;
    }
}
