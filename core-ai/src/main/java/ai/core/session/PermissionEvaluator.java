package ai.core.session;

import java.util.ArrayList;
import java.util.List;

public class PermissionEvaluator {
    private final List<PermissionRule> rules;

    public PermissionEvaluator(List<PermissionRule> rules) {
        this.rules = rules;
    }

    public PermissionRule.PermissionAction evaluate(String toolName, String arguments) {
        var result = PermissionRule.PermissionAction.ASK;
        for (var rule : rules) {
            if (rule.matches(toolName, arguments)) {
                result = rule.action();
            }
        }
        return result;
    }

    @SafeVarargs
    public static PermissionEvaluator merge(List<PermissionRule>... ruleSets) {
        var merged = new ArrayList<PermissionRule>();
        for (var ruleSet : ruleSets) {
            if (ruleSet != null) merged.addAll(ruleSet);
        }
        return new PermissionEvaluator(merged);
    }
}
