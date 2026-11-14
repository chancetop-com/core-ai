package ai.core.server.quality;

import core.framework.web.WebContext;

/**
 * Publish responses keep their existing body; quality metadata travels in headers and the latest-report API.
 * The controller stores them in the web context and the CORS interceptor copies them onto the response.
 *
 * @author core-ai
 */
public final class QualityHeaders {
    public static final String RUN_ID = "X-Quality-Run-Id";
    public static final String GATE_RESULT = "X-Quality-Gate-Result";
    public static final String WARN_COUNT = "X-Quality-Warn-Count";

    public static final String CONTEXT_RUN_ID = "quality.runId";
    public static final String CONTEXT_GATE_RESULT = "quality.gateResult";
    public static final String CONTEXT_WARN_COUNT = "quality.warnCount";

    public static void apply(WebContext context, core.framework.web.Response response) {
        header(context, response, CONTEXT_RUN_ID, RUN_ID);
        header(context, response, CONTEXT_GATE_RESULT, GATE_RESULT);
        header(context, response, CONTEXT_WARN_COUNT, WARN_COUNT);
    }

    public static void put(WebContext context, String runId, String gateResult, int warnCount) {
        if (runId != null) context.put(CONTEXT_RUN_ID, runId);
        if (gateResult != null) context.put(CONTEXT_GATE_RESULT, gateResult);
        context.put(CONTEXT_WARN_COUNT, String.valueOf(warnCount));
    }

    private static void header(WebContext context, core.framework.web.Response response, String key, String name) {
        var value = context.get(key);
        if (value == null) return;
        response.header(name, String.valueOf(value));
    }

    private QualityHeaders() {
    }
}
