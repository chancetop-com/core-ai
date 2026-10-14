package ai.core.api.server.runtime;

import core.framework.api.json.Property;

import java.util.List;

/**
 * The fleet view for the runtime dashboard: the limits in force plus the newest sample of every replica
 * that has reported inside the lookback window.
 *
 * @author stephen
 */
public class RuntimePodsView {
    @Property(name = "limits")
    public RuntimeLimitsView limits;

    @Property(name = "pods")
    public List<RuntimePodView> pods;
}
