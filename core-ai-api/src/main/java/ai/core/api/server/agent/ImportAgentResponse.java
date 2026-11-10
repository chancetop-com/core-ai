package ai.core.api.server.agent;

import core.framework.api.json.Property;

import java.util.List;

public class ImportAgentResponse {
    @Property(name = "agent")
    public AgentDefinitionView agent;   // null for a preview import

    @Property(name = "config")
    public AgentImportConfigView config;   // resolved configuration, also present for a preview import

    @Property(name = "unresolved_references")
    public List<AgentUnresolvedReferenceView> unresolvedReferences;
}
