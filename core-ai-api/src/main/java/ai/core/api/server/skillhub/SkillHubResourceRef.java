package ai.core.api.server.skillhub;

import core.framework.api.json.Property;

/**
 * Resource metadata in a skill detail: path plus content fingerprint. Content itself
 * is fetched through the dedicated single-resource endpoint.
 *
 * @author stephen
 */
public class SkillHubResourceRef {
    @Property(name = "path")
    public String path;

    @Property(name = "size")
    public Integer size;

    @Property(name = "sha256")
    public String sha256;
}
