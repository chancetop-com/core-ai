package ai.core.server.skill.quality;

import core.framework.web.WebContext;

/**
 * Skill publish responses keep their existing body; the run summary travels in headers so older clients
 * keep working. CORS exposes these through the same interceptor used by the Agent gate.
 *
 * @author core-ai
 */
public final class SkillQualityHeaders {
    public static final String RUN_ID = "X-Skill-Quality-Run-Id";
    public static final String GATE_RESULT = "X-Skill-Quality-Gate-Result";
    public static final String WARN_COUNT = "X-Skill-Quality-Warn-Count";

    private static final String CONTEXT_RUN_ID = "skill.quality.runId";
    private static final String CONTEXT_GATE_RESULT = "skill.quality.gateResult";
    private static final String CONTEXT_WARN_COUNT = "skill.quality.warnCount";

    public static void put(WebContext context, SkillQualityReport report) {
        if (report == null) return;
        if (report.runId != null) context.put(CONTEXT_RUN_ID, report.runId);
        if (report.gateResult != null) context.put(CONTEXT_GATE_RESULT, report.gateResult.name());
        context.put(CONTEXT_WARN_COUNT, String.valueOf(report.warnCount));
    }

    public static void apply(WebContext context, core.framework.web.Response response) {
        header(context, response, CONTEXT_RUN_ID, RUN_ID);
        header(context, response, CONTEXT_GATE_RESULT, GATE_RESULT);
        header(context, response, CONTEXT_WARN_COUNT, WARN_COUNT);
    }

    private static void header(WebContext context, core.framework.web.Response response, String key, String name) {
        var value = context.get(key);
        if (value != null) response.header(name, String.valueOf(value));
    }

    private SkillQualityHeaders() {
    }
}
