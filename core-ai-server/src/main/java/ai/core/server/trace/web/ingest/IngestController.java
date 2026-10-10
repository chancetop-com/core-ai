package ai.core.server.trace.web.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;

import core.framework.inject.Inject;
import core.framework.web.Request;
import core.framework.web.Response;
import core.framework.web.WebContext;

import ai.core.server.rbac.PermissionsBypass;
import ai.core.server.trace.service.IngestService;
import ai.core.server.web.auth.AuthContext;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.util.Map;

/**
 * @author Xander
 */
public class IngestController {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final String SOURCE_CLI = "cli";
    // Client-declared trace origins; extend this table when a new client ships. Anything unlisted (and older
    // clients that send no clientType) keeps the CLI default.
    private static final Map<String, String> SOURCE_BY_CLIENT_TYPE = Map.of("desktop", "desktop");

    static String traceSource(String clientType) {
        return clientType == null ? SOURCE_CLI : SOURCE_BY_CLIENT_TYPE.getOrDefault(clientType, SOURCE_CLI);
    }

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

    // Authenticated CLI/SDK path: userId resolved from Bearer by AuthInterceptor; source from the client type
    // (cli by default, "desktop" when a desktop app drives the engine).
    @PermissionsBypass
    @SuppressFBWarnings("REC_CATCH_EXCEPTION")
    public Response ingestAuthed(Request request) {
        // Fail loud if this handler is ever reached without authentication (e.g. route whitelisted by mistake);
        // a null userId must never silently become an anonymous, mis-attributed trace.
        var userId = AuthContext.userId(webContext);
        if (userId == null) throw new IllegalStateException("authenticated ingest requires a userId");
        try {
            byte[] body = request.body().orElseThrow(() -> new IllegalArgumentException("empty body"));
            var ingestRequest = MAPPER.readValue(body, IngestRequest.class);
            ingestService.ingest(ingestRequest, userId, traceSource(ingestRequest.clientType));
            return Response.text("ok");
        } catch (Exception e) {
            return Response.text("bad request: " + e.getMessage()).status(core.framework.api.http.HTTPStatus.BAD_REQUEST);
        }
    }
}
