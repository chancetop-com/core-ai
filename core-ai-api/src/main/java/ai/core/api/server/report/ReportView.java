package ai.core.api.server.report;

import core.framework.api.json.Property;

/**
 * @author core-ai
 */
public class ReportView {
    @Property(name = "id")
    public String id;

    @Property(name = "title")
    public String title;

    @Property(name = "description")
    public String description;

    @Property(name = "revision")
    public Long revision;

    @Property(name = "spec_version")
    public Integer specVersion;

    @Property(name = "engine_version")
    public Integer engineVersion;

    @Property(name = "dataset_id")
    public String datasetId;

    @Property(name = "dataset_name")
    public String datasetName;

    @Property(name = "display_type")
    public String displayType;

    @Property(name = "spec_json")
    public String specJson;

    @Property(name = "parameter_options_json")
    public String parameterOptionsJson;

    @Property(name = "pin_id")
    public String pinId;

    @Property(name = "pin_width")
    public String pinWidth;

    @Property(name = "created_at")
    public String createdAt;

    @Property(name = "updated_at")
    public String updatedAt;
}
