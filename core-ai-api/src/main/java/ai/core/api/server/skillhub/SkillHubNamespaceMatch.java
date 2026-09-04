package ai.core.api.server.skillhub;

import core.framework.api.json.Property;

/**
 * A namespace that has matched skills inside a hub search, with the namespace-level
 * (brand) score and how many skills matched under it.
 *
 * @author stephen
 */
public class SkillHubNamespaceMatch {
    @Property(name = "namespace")
    public String namespace;

    @Property(name = "matched_count")
    public Integer matchedCount;

    @Property(name = "score")
    public Integer score;
}
