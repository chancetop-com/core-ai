package ai.core.server.trace.web.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;

import core.framework.inject.Inject;
import core.framework.web.Request;
import core.framework.web.Response;
import core.framework.web.WebContext;

import ai.core.server.trace.service.IngestService;
import ai.core.server.web.auth.AuthContext;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * @author Xander
 */
public class IngestController {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final String SOURCE_CLI = "cli";

    @Inject
    IngestService ingestService;
    @Inject
    WebContext webContext;

    // Anonymous legacy path: trusts client-supplied user.id, no source stamp.
    @SuppressFBWarnings("REC_CATCH_EXCEPTION")
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

    // Authenticated CLI/SDK path: userId resolved from Bearer by AuthInterceptor; source forced to "cli".
    @SuppressFBWarnings("REC_CATCH_EXCEPTION")
    public Response ingestAuthed(Request request) {
        // Fail loud if this handler is ever reached without authentication (e.g. route whitelisted by mistake);
        // a null userId must never silently become an anonymous, mis-attributed trace.
        var userId = AuthContext.userId(webContext);
        if (userId == null) throw new IllegalStateException("authenticated ingest requires a userId");
        try {
            byte[] body = request.body().orElseThrow(() -> new IllegalArgumentException("empty body"));
            var ingestRequest = MAPPER.readValue(body, IngestRequest.class);
            ingestService.ingest(ingestRequest, userId, SOURCE_CLI);
            return Response.text("ok");
        } catch (Exception e) {
            return Response.text("bad request: " + e.getMessage()).status(core.framework.api.http.HTTPStatus.BAD_REQUEST);
        }
    }
}
