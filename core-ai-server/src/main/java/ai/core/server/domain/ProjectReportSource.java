package ai.core.server.domain;

import core.framework.mongo.Field;

/**
 * A member (agent or workflow) whose produced artifacts count as reports this project evaluates
 * (e.g. the SEO audit agent). The name is snapshotted at definition time so display survives
 * member removal.
 *
 * @author stephen
 */
public class ProjectReportSource {
    @Field(name = "type")
    public String type;   // agent | workflow

    @Field(name = "id")
    public String id;

    @Field(name = "name")
    public String name;
}
