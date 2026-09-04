package ai.core.api.server.skillhub;

import core.framework.api.json.Property;

import java.util.List;

/**
 * Search response with two levels: {@code namespaces} lists every namespace with
 * matched skills (brand layer first, matched counts attached) and {@code skills}
 * carries the diversified top picks (at most 3 per namespace) ordered round-robin
 * by namespace score. A query-less listing returns flat skills and no namespaces.
 *
 * @author stephen
 */
public class SkillHubSearchResponse {
    @Property(name = "namespaces")
    public List<SkillHubNamespaceMatch> namespaces;

    @Property(name = "skills")
    public List<SkillHubSummary> skills;
}
