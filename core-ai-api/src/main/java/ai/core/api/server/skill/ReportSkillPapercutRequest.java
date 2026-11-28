package ai.core.api.server.skill;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * Request body for POST /api/skills/:id/papercuts.
 *
 * @author stephen
 */
public class ReportSkillPapercutRequest {
    @NotNull
    @Property(name = "category")
    public SkillPapercutCategoryView category;

    @NotNull
    @Property(name = "summary")
    public String summary;

    @Property(name = "observed_error")
    public String observedError;

    @Property(name = "suggested_fix")
    public String suggestedFix;

    @Property(name = "package_digest")
    public String packageDigest;

    @Property(name = "session_id")
    public String sessionId;
}
