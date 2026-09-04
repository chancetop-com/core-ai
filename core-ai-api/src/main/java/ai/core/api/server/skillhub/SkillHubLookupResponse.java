package ai.core.api.server.skillhub;

import core.framework.api.json.Property;

import java.util.List;

/**
 * Bare-name resolution result: all catalog skills whose {@code name} equals the
 * requested value, one per namespace. Callers pick the single candidate or report
 * the ambiguity to the user.
 *
 * @author stephen
 */
public class SkillHubLookupResponse {
    @Property(name = "candidates")
    public List<SkillHubSummary> candidates;
}
