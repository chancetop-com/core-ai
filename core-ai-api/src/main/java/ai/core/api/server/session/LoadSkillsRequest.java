package ai.core.api.server.session;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.List;

/**
 * @author stephen
 */
public class LoadSkillsRequest {
    @NotNull
    @Property(name = "skill_ids")
    public List<String> skillIds = List.of();

    /**
     * Optional digest returned by the SDK capability preview. When present, the server rejects the
     * load if the current declarations no longer match what the user saw.
     */
    @Property(name = "sdk_plan_digest")
    public String sdkPlanDigest;
}
