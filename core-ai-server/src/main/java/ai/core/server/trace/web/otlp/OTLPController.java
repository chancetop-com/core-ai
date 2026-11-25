package ai.core.server.trace.web.otlp;

import core.framework.inject.Inject;
import core.framework.web.Request;
import core.framework.web.Response;

import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ai.core.server.trace.service.MemoryTraceSpanBuffer;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * OTLP HTTP protobuf receiver
 * Compatible with OpenTelemetry SDK OtlpHttpSpanExporter
 *
 * @author Xander
 */
public class OTLPController {
    private static final Logger LOGGER = LoggerFactory.getLogger(OTLPController.class);

    @Inject
    MemoryTraceSpanBuffer traceBuffer;

    @SuppressFBWarnings("REC_CATCH_EXCEPTION")
    public Response receive(Request request) {
        byte[] body;
        ExportTraceServiceRequest exportRequest;
        try {
            body = request.body().orElseThrow(() -> new IllegalArgumentException("empty body"));
            exportRequest = ExportTraceServiceRequest.parseFrom(body);
        } catch (Exception e) {
            LOGGER.warn("failed to parse OTLP request", e);
            return Response.text("bad request").status(core.framework.api.http.HTTPStatus.BAD_REQUEST);
        }
        if (!traceBuffer.offerOtlp(exportRequest, body.length)) {
            return Response.text("trace ingest busy").status(core.framework.api.http.HTTPStatus.SERVICE_UNAVAILABLE);
        }
        byte[] responseBytes = ExportTraceServiceResponse.getDefaultInstance().toByteArray();
        return Response.bytes(responseBytes).contentType(core.framework.http.ContentType.APPLICATION_OCTET_STREAM);
    }
}
