package ai.core.server.workflow.executor;

import ai.core.server.workflow.NodeContext;
import ai.core.server.workflow.NodeExecutor;
import ai.core.server.workflow.NodeOutcome;
import core.framework.json.JSON;

import java.util.LinkedHashMap;

/**
 * HUMAN_INPUT node: parks the run until a human responds. On its single execution it renders the prompt over the
 * variable pool (so it can show an upstream value, e.g. "confirm: {{ nodes.draft.output }}") and returns
 * {@link NodeOutcome.Waiting} — the run goes PAUSED. It is NOT re-run on resume; the resume endpoint settles this
 * node's WAITING node-run to COMPLETED with the human's input (mode=input) or chosen branch (mode=approval).
 *
 * <p>config: {@code mode} ("approval" | "input"), {@code prompt} (templated), plus the static UI schema the
 * frontend reads directly (approval: {@code approve_edge_id}/{@code reject_edge_id}; input: {@code fields}).
 *
 * @author Xander
 */
public class HumanInputExecutor implements NodeExecutor {
    @Override
    public NodeOutcome execute(NodeContext ctx) {
        var config = ctx.node().config();
        String mode = config.get("mode") instanceof String value && !value.isBlank() ? value : "approval";
        String prompt = config.get("prompt") instanceof String template ? ctx.pool().render(template) : "";
        // the rendered ask the UI shows the human; the static form/branch schema stays in the node config
        var ask = new LinkedHashMap<String, Object>();
        ask.put("mode", mode);
        ask.put("prompt", prompt);
        return new NodeOutcome.Waiting(JSON.toJSON(ask));
    }
}
