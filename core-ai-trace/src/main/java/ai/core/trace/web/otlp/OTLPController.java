package ai.core.trace.web.otlp;

import core.framework.inject.Inject;
import core.framework.web.Request;
import core.framework.web.Response;

import ai.core.trace.service.TraceService;

/**
 * @author Xander
 */
public class OTLPController {
    @Inject
    TraceService traceService;

    // TODO: implement OTLP protobuf/JSON trace ingestion
    public Response receive(Request request) {
        return Response.text("ok");
    }
}
