package ai.core.server.agent;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgAgentSummaryRow {
    @Column(name = "id")
    public String id;

    @Column(name = "name")
    public String name;

    @Column(name = "type")
    public String type;

    @Column(name = "status")
    public String status;

    @Column(name = "description")
    public String description;

    @Column(name = "published_name")
    public String publishedName;

    @Column(name = "published_description")
    public String publishedDescription;
}
