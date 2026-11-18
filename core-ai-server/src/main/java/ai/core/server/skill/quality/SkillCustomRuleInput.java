package ai.core.server.skill.quality;

/**
 * Allowlisted inputs an admin-composed skill rule may read. The evaluator packs only these values, so a
 * rule cannot reach arbitrary server state.
 *
 * @author core-ai
 */
public final class SkillCustomRuleInput {
    public static final String DESCRIPTION = "DESCRIPTION";
    public static final String SKILL_CONTENT = "SKILL_CONTENT";
    public static final String METADATA = "METADATA";
    public static final String ALLOWED_TOOLS = "ALLOWED_TOOLS";
    public static final String RESOURCE_MANIFEST = "RESOURCE_MANIFEST";
    public static final String EVAL_DATASET = "EVAL_DATASET";

    private SkillCustomRuleInput() {
    }
}
