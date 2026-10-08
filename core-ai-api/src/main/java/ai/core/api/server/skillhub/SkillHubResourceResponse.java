package ai.core.api.server.skillhub;

import core.framework.api.json.Property;

/**
 * Single resource content of a skill ({@code path} must be an exact registered
 * resource path — no filesystem resolution happens on the server). Text resources
 * return their content as-is; binary resources return base64 with {@code encoding=base64}
 * when they fit the inline cap, and must be fetched through the archive endpoint otherwise.
 *
 * @author stephen
 */
public class SkillHubResourceResponse {
    @Property(name = "path")
    public String path;

    @Property(name = "kind")
    public String kind;

    @Property(name = "content")
    public String content;

    @Property(name = "encoding")
    public String encoding;

    @Property(name = "size")
    public Integer size;

    @Property(name = "sha256")
    public String sha256;
}
