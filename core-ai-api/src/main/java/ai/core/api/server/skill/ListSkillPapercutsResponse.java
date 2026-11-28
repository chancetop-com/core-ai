package ai.core.api.server.skill;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.List;

/**
 * Response body for GET /api/skills/:id/papercuts.
 *
 * @author stephen
 */
public class ListSkillPapercutsResponse {
    @NotNull
    @Property(name = "telemetry_available")
    public Boolean telemetryAvailable;

    @NotNull
    @Property(name = "papercuts")
    public List<SkillPapercutView> papercuts;
}
