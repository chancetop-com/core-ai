package ai.core.api.server.costalert.response;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListCostAlertRulesResponse {
    @Property(name = "rules")
    public List<CostAlertRuleView> rules;
}
