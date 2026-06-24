package ai.core.api.server.workflow;

import core.framework.api.json.Property;

/**
 * @author Xander
 */
public class WorkflowView {
    @Property(name = "id")
    public String id;

    @Property(name = "user_id")
    public String userId;   // owner; lets the discover list show the author and distinguish from the caller

    @Property(name = "user_name")
    public String userName;   // resolved owner display name; populated on list, null elsewhere

    @Property(name = "editable")
    public Boolean editable;   // true when the caller owns it; false = read-only (other user's published). null = unknown

    @Property(name = "name")
    public String name;

    @Property(name = "mode")
    public String mode;

    @Property(name = "status")
    public String status;   // DRAFT | PUBLISHED

    @Property(name = "published_version")
    public Integer publishedVersion;

    @Property(name = "published_version_id")
    public String publishedVersionId;

    @Property(name = "draft_graph")
    public String draftGraph;   // included on get/list for the editor; null on create/publish responses
}
