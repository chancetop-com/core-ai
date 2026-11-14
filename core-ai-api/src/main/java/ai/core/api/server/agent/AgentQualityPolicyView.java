package ai.core.api.server.agent;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * @author core-ai
 */
public class AgentQualityPolicyView {
    @NotNull
    @Property(name = "revision")
    public Integer revision;

    @NotNull
    @Property(name = "generation")
    public Integer generation;

    @Property(name = "scope")
    public String scope;

    @Property(name = "active")
    public Boolean active;

    @Property(name = "change_reason")
    public String changeReason;

    @Property(name = "activated_by")
    public String activatedBy;

    @Property(name = "activated_at")
    public ZonedDateTime activatedAt;

    @Property(name = "bindings")
    public List<AgentQualityPolicyBindingView> bindings;
}
