package ai.core.api.server.workflow;

import core.framework.api.json.Property;

/**
 * A graph reference (agent or MCP tool) that does not resolve for the importing user. Reported as a warning;
 * the draft is still created and can be fixed in the editor before publishing.
 *
 * @author Xander
 */
public class UnresolvedReferenceView {
    @Property(name = "node_id")
    public String nodeId;

    @Property(name = "node_type")
    public String nodeType;

    @Property(name = "ref_type")
    public String refType;   // AGENT | MCP_TOOL

    @Property(name = "ref_id")
    public String refId;

    @Property(name = "message")
    public String message;
}
