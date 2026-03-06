package ai.core.session;

import ai.core.api.server.session.ApprovalDecision;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PermissionModelTest {

    @Test
    void ruleMatchesExactToolName() {
        var rule = new PermissionRule("read_file", null, PermissionRule.PermissionAction.ALLOW);
        assertEquals(true, rule.matches("read_file", "{}"));
        assertEquals(false, rule.matches("write_file", "{}"));
    }

    @Test
    void ruleMatchesGlobPattern() {
        var rule = new PermissionRule("read_*", null, PermissionRule.PermissionAction.ALLOW);
        assertEquals(true, rule.matches("read_file", "{}"));
        assertEquals(true, rule.matches("read_directory", "{}"));
        assertEquals(false, rule.matches("write_file", "{}"));
    }

    @Test
    void ruleMatchesWildcard() {
        var rule = new PermissionRule("*", null, PermissionRule.PermissionAction.ALLOW);
        assertEquals(true, rule.matches("any_tool", "{}"));
    }

    @Test
    void ruleMatchesArgumentPattern() {
        var rule = new PermissionRule("shell_command", ".*rm\\s+-rf.*", PermissionRule.PermissionAction.DENY);
        assertEquals(true, rule.matches("shell_command", "{\"command\": \"rm -rf /tmp\"}"));
        assertEquals(false, rule.matches("shell_command", "{\"command\": \"ls -la\"}"));
    }

    @Test
    void ruleWithNullArgumentPatternMatchesAnyArgs() {
        var rule = new PermissionRule("tool", null, PermissionRule.PermissionAction.ALLOW);
        assertEquals(true, rule.matches("tool", null));
        assertEquals(true, rule.matches("tool", "{}"));
        assertEquals(true, rule.matches("tool", "{\"anything\": true}"));
    }

    @Test
    void evaluatorDefaultsToAsk() {
        var evaluator = new PermissionEvaluator(List.of());
        assertEquals(PermissionRule.PermissionAction.ASK, evaluator.evaluate("any_tool", "{}"));
    }

    @Test
    void evaluatorLastMatchWins() {
        var evaluator = new PermissionEvaluator(List.of(
                new PermissionRule("*", null, PermissionRule.PermissionAction.ALLOW),
                new PermissionRule("shell_command", null, PermissionRule.PermissionAction.ASK),
                new PermissionRule("shell_command", ".*rm\\s+-rf.*", PermissionRule.PermissionAction.DENY)
        ));

        assertEquals(PermissionRule.PermissionAction.ALLOW, evaluator.evaluate("read_file", "{}"));
        assertEquals(PermissionRule.PermissionAction.ASK, evaluator.evaluate("shell_command", "{\"command\": \"ls\"}"));
        assertEquals(PermissionRule.PermissionAction.DENY, evaluator.evaluate("shell_command", "{\"command\": \"rm -rf /\"}"));
    }

    @Test
    void evaluatorMergesCombinesRuleSets() {
        var agentRules = List.of(
                new PermissionRule("read_*", null, PermissionRule.PermissionAction.ALLOW)
        );
        var sessionRules = List.of(
                new PermissionRule("shell_command", null, PermissionRule.PermissionAction.DENY)
        );

        var evaluator = PermissionEvaluator.merge(agentRules, sessionRules);

        assertEquals(PermissionRule.PermissionAction.ALLOW, evaluator.evaluate("read_file", "{}"));
        assertEquals(PermissionRule.PermissionAction.DENY, evaluator.evaluate("shell_command", "{}"));
        assertEquals(PermissionRule.PermissionAction.ASK, evaluator.evaluate("write_file", "{}"));
    }

    @Test
    void evaluatorMergeHandlesNullRuleSets() {
        var evaluator = PermissionEvaluator.merge(null, List.of(
                new PermissionRule("*", null, PermissionRule.PermissionAction.ALLOW)
        ), null);

        assertEquals(PermissionRule.PermissionAction.ALLOW, evaluator.evaluate("any_tool", "{}"));
    }

    @Test
    void autoApproveAllBackwardCompatibility() {
        // When autoApproveAll=true, all tools should be allowed
        var evaluator = new PermissionEvaluator(List.of(
                new PermissionRule("*", null, PermissionRule.PermissionAction.ALLOW)
        ));
        assertEquals(PermissionRule.PermissionAction.ALLOW, evaluator.evaluate("any_tool", "{}"));
        assertEquals(PermissionRule.PermissionAction.ALLOW, evaluator.evaluate("shell_command", "{\"cmd\": \"rm -rf /\"}"));
    }

    @Test
    void permissionStoreRulesConvertToAllowRules() {
        var storeRules = List.of("read_file", "write_file").stream()
                .map(name -> new PermissionRule(name, null, PermissionRule.PermissionAction.ALLOW))
                .toList();

        var evaluator = new PermissionEvaluator(storeRules);

        assertEquals(PermissionRule.PermissionAction.ALLOW, evaluator.evaluate("read_file", "{}"));
        assertEquals(PermissionRule.PermissionAction.ALLOW, evaluator.evaluate("write_file", "{}"));
        assertEquals(PermissionRule.PermissionAction.ASK, evaluator.evaluate("shell_command", "{}"));
    }

    @Test
    void permissionGateCancelAll() {
        var gate = new PermissionGate();
        gate.prepare("call_1");
        gate.prepare("call_2");

        gate.cancelAll();

        // After cancelAll, pending futures should be completed with DENY
        // New prepare + waitForApproval should work normally
        gate.prepare("call_3");
        gate.respond("call_3", ApprovalDecision.APPROVE);
        var decision = gate.waitForApproval("call_3", 1000);
        assertEquals(ApprovalDecision.APPROVE, decision);
    }
}
