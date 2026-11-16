package ai.core.api.server.skill;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * Read-only metadata of one built-in skill quality rule. Rules are defined in code, so this view has no
 * edit surface: a change requires a release.
 *
 * @author core-ai
 */
public class SkillQualityRuleView {
    @NotNull
    @Property(name = "key")
    public String key;

    @NotNull
    @Property(name = "name")
    public String name;

    @Property(name = "revision")
    public Integer revision;

    @Property(name = "category")
    public String category;

    @Property(name = "enforcement")
    public String enforcement;

    @Property(name = "requirement")
    public String requirement;
}
