package ai.core.server.trace.web.ingest;

import core.framework.inject.Inject;
import core.framework.web.Request;
import core.framework.web.Response;

import ai.core.server.trace.service.IngestService;

/**
 * @author Xander
 */
public class IngestController {
    @Inject
    IngestService ingestService;

    public Response ingestSpans(Request request) {
        var body = request.bean(IngestRequest.class);
        ingestService.ingest(body);
        return Response.text("ok");
    }
}
