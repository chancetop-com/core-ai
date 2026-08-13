package ai.core.api.server.systemprompt;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class SystemPromptVersionView {
    @Property(name = "version")
    public Integer version;

    @Property(name = "changelog")
    public String changelog;

    @Property(name = "content")
    public String content;

    @Property(name = "createdAt")
    public ZonedDateTime createdAt;
}
