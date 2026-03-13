package ai.core.server.trace.web.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;

import core.framework.inject.Inject;
import core.framework.web.Request;
import core.framework.web.Response;

import ai.core.server.trace.service.IngestService;

/**
 * @author Xander
 */
public class IngestController {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Inject
    IngestService ingestService;

    public Response ingestSpans(Request request) {
        try {
            byte[] body = request.body().orElseThrow(() -> new IllegalArgumentException("empty body"));
            var ingestRequest = MAPPER.readValue(body, IngestRequest.class);
            ingestService.ingest(ingestRequest);
            return Response.text("ok");
        } catch (Exception e) {
            return Response.text("bad request: " + e.getMessage()).status(core.framework.api.http.HTTPStatus.BAD_REQUEST);
        }
    }
}
