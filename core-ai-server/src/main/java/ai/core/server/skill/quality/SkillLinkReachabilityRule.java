package ai.core.server.skill.quality;

import ai.core.server.quality.domain.QualityEnforcement;
import ai.core.server.quality.domain.QualityIssueStatus;
import ai.core.server.quality.domain.StoredQualityIssue;

import java.util.ArrayList;
import java.util.LinkedHashSet;

/**
 * Verifies that local Markdown links in SKILL.md resolve to packaged resources and that required
 * sections do not rely solely on external URLs. Split from SKILL.PROGRESSIVE_DISCLOSURE so link
 * integrity can block at DOMAIN while the 5-section structure warns.
 *
 * @author stephen
 */
public final class SkillLinkReachabilityRule implements SkillQualityRule {
    public static final String KEY = "SKILL.LINK_REACHABILITY";
    private static final SkillRuleMeta META = new SkillRuleMeta(
        KEY, 1, "Link reachability",
        "Local Markdown links in SKILL.md must resolve to packaged files and required sections must not point only to external URLs.",
        "CONTENT", QualityEnforcement.BLOCK);

    private final SkillDisclosureResolver resolver = new SkillDisclosureResolver();

    @Override
    public SkillRuleMeta meta() {
        return META;
    }

    @Override
    public SkillRuleResult evaluate(SkillPublishCandidate candidate) {
        var resolution = resolver.resolve(candidate);
        var issues = new ArrayList<StoredQualityIssue>();
        var reportedBroken = new LinkedHashSet<String>();
        for (var broken : resolution.brokenLinks()) {
            if (reportedBroken.add(broken)) {
                issues.add(issue("REFERENCE_NOT_PACKAGED",
                    "Link target is not packaged with this upload: " + broken,
                    "Add the file to the skill directory and publish again, or remove the link."));
            }
        }
        if (candidate.document != null) {
            for (var link : candidate.document.markdownLinks()) {
                var resolved = SkillDisclosureResolver.resolvePath(link.target);
                if (resolved != null && candidate.resources.containsKey(resolved)) continue;
                var key = resolved != null ? resolved : link.target;
                if (reportedBroken.add(key)) {
                    issues.add(issue("REFERENCE_NOT_PACKAGED",
                        "Link target is not packaged with this upload: " + link.target,
                        "Add the file to the skill directory and publish again, or remove the link."));
                }
            }
        }
        var byKey = SkillDisclosureRequirements.byKey();
        for (var remoteKey : resolution.remoteRequirements()) {
            var requirement = byKey.get(remoteKey);
            var label = requirement == null ? remoteKey : requirement.label();
            issues.add(issue("REMOTE_SECTION_NOT_ALLOWED",
                label + " points only to an external URL; the gate cannot guarantee access.",
                "Put the content in a packaged reference and link it from SKILL.md."));
        }
        return issues.isEmpty() ? SkillRuleResult.pass() : new SkillRuleResult(QualityIssueStatus.FAIL, issues);
    }

    private StoredQualityIssue issue(String reason, String message, String suggestion) {
        return SkillQualityIssues.issue(META, QualityIssueStatus.FAIL, "/SKILL.md", reason, message, suggestion);
    }
}
