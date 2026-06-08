package ai.core.server.workflow.executor;

import ai.core.server.workflow.NodeContext;
import ai.core.server.workflow.NodeExecutor;
import ai.core.server.workflow.NodeOutcome;
import ai.core.server.workflow.VariablePool;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * IF/ELSE node: evaluate ordered cases over the variable pool and activate exactly one out-edge. The first case
 * whose conditions hold picks its {@code edge_id}; if none match, the {@code else_edge_id} is taken. The engine's
 * edge-verdict logic turns the chosen edge ACTIVE and SKIPs the rest, so branch selection is just a
 * {@link NodeOutcome.Branch} — the executor never touches topology.
 *
 * <p>Config shape (selectors/edge ids are authored on the canvas):
 * <pre>{ "cases": [ { "logic": "and", "edge_id": "e_refund",
 *                    "conditions": [ { "selector": "nodes.start.output.type", "operator": "eq", "value": "refund" } ] } ],
 *        "else_edge_id": "e_default" }</pre>
 *
 * @author Xander
 */
public class IfElseExecutor implements NodeExecutor {
    @Override
    public NodeOutcome execute(NodeContext ctx) {
        Map<String, Object> config = ctx.node().config();
        List<?> cases = config.get("cases") instanceof List<?> list ? list : List.of();
        for (Object element : cases) {
            if (element instanceof Map<?, ?> caseMap && matches(caseMap, ctx.pool())) {
                String edgeId = str(caseMap.get("edge_id"));
                if (edgeId != null) {
                    return new NodeOutcome.Branch("{}", List.of(edgeId));
                }
            }
        }
        String elseEdge = str(config.get("else_edge_id"));
        return new NodeOutcome.Branch("{}", elseEdge != null ? List.of(elseEdge) : List.of());
    }

    private static boolean matches(Map<?, ?> caseMap, VariablePool pool) {
        List<?> conditions = caseMap.get("conditions") instanceof List<?> list ? list : List.of();
        if (conditions.isEmpty()) {
            return true;   // a case with no conditions is the unconditional (else-style) branch
        }
        boolean or = "or".equalsIgnoreCase(str(caseMap.get("logic")));
        boolean result = !or;   // AND folds from true, OR folds from false
        for (Object element : conditions) {
            if (element instanceof Map<?, ?> condition) {
                boolean one = evaluate(condition, pool);
                result = or ? result || one : result && one;
            }
        }
        return result;
    }

    private static boolean evaluate(Map<?, ?> condition, VariablePool pool) {
        String selector = str(condition.get("selector"));
        String operator = str(condition.get("operator"));
        String value = str(condition.get("value"));
        Object resolved = selector == null ? null : pool.resolve(selector).orElse(null);
        String actual = resolved == null ? null : String.valueOf(resolved);
        return switch (operator == null ? "" : operator) {
            case "eq", "==" -> Objects.equals(actual, value);
            case "ne", "!=" -> !Objects.equals(actual, value);
            case "contains" -> actual != null && value != null && actual.contains(value);
            case "not_contains" -> actual == null || value == null || !actual.contains(value);
            case "empty", "is_empty" -> actual == null || actual.isEmpty();
            case "not_empty", "is_not_empty" -> actual != null && !actual.isEmpty();
            case "gt", "lt", "gte", "lte" -> compareNumeric(actual, value, operator);
            default -> false;
        };
    }

    private static boolean compareNumeric(String a, String b, String operator) {
        double left;
        double right;
        try {
            left = Double.parseDouble(a);
            right = Double.parseDouble(b);
        } catch (NumberFormatException | NullPointerException e) {
            return false;   // unparseable operands never satisfy a numeric comparison
        }
        int c = Double.compare(left, right);
        return switch (operator) {
            case "gt" -> c > 0;
            case "lt" -> c < 0;
            case "gte" -> c >= 0;
            case "lte" -> c <= 0;
            default -> false;
        };
    }

    private static String str(Object value) {
        return value == null ? null : value instanceof String string ? string : String.valueOf(value);
    }
}
