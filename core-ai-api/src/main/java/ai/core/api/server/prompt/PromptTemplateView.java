package ai.core.api.server.prompt;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class PromptTemplateView {
    @Property(name = "id")
    public String id;

    @Property(name = "name")
    public String name;

    @Property(name = "description")
    public String description;

    @Property(name = "template")
    public String template;

    @Property(name = "variables")
    public List<String> variables;

    @Property(name = "model")
    public String model;

    @Property(name = "modelParameters")
    public Map<String, String> modelParameters;

    @Property(name = "version")
    public Integer version;

    @Property(name = "publishedVersion")
    public Integer publishedVersion;

    @Property(name = "status")
    public PromptStatusView status;

    @Property(name = "tags")
    public List<String> tags;

    @Property(name = "createdBy")
    public String createdBy;

    @Property(name = "createdAt")
    public ZonedDateTime createdAt;

    @Property(name = "updatedAt")
    public ZonedDateTime updatedAt;
}
