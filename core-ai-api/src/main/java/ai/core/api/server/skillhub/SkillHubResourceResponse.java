package ai.core.api.server.skillhub;

import core.framework.api.json.Property;

/**
 * Single resource content of a skill ({@code path} must be an exact registered
 * resource path — no filesystem resolution happens on the server).
 *
 * @author stephen
 */
public class SkillHubResourceResponse {
    @Property(name = "path")
    public String path;

    @Property(name = "content")
    public String content;

    @Property(name = "size")
    public Integer size;

    @Property(name = "sha256")
    public String sha256;
}
