package ai.core.server.trace.web.otlp;

import core.framework.inject.Inject;
import core.framework.web.Request;
import core.framework.web.Response;

import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ai.core.server.trace.service.OTLPIngestService;

/**
 * OTLP HTTP protobuf receiver
 * Compatible with OpenTelemetry SDK OtlpHttpSpanExporter
 *
 * @author Xander
 */
public class OTLPController {
    private static final Logger LOGGER = LoggerFactory.getLogger(OTLPController.class);

    @Inject
    OTLPIngestService otlpIngestService;

    public Response receive(Request request) {
        try {
            byte[] body = request.body().orElseThrow(() -> new IllegalArgumentException("empty body"));
            var exportRequest = ExportTraceServiceRequest.parseFrom(body);
            otlpIngestService.ingest(exportRequest);
            byte[] responseBytes = ExportTraceServiceResponse.getDefaultInstance().toByteArray();
            return Response.bytes(responseBytes).contentType(core.framework.http.ContentType.APPLICATION_OCTET_STREAM);
        } catch (Exception e) {
            LOGGER.warn("failed to parse OTLP request", e);
            return Response.text("bad request").status(core.framework.api.http.HTTPStatus.BAD_REQUEST);
        }
    }
}
