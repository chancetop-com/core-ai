package ai.core.api.server.agent;

import core.framework.api.json.Property;

import java.util.List;

/**
 * One broken or unverifiable reference in either the editable draft or the published snapshot.
 *
 * @author core-ai
 */
public class AgentReferenceIssueView {
    @Property(name = "ref_type")
    public String refType;

    @Property(name = "ref_id")
    public String refId;

    @Property(name = "ref_name")
    public String refName;

    @Property(name = "state")
    public String state;

    @Property(name = "reason")
    public String reason;

    @Property(name = "sources")
    public List<String> sources;

    @Property(name = "in_draft")
    public Boolean inDraft;

    @Property(name = "in_published")
    public Boolean inPublished;

    @Property(name = "message")
    public String message;
}
