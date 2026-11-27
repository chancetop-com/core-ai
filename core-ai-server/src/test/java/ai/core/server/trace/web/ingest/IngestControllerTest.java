package ai.core.server.trace.web.ingest;

import ai.core.server.rbac.PermissionsBypass;
import ai.core.server.trace.service.MemoryTraceSpanBuffer;
import ai.core.server.web.auth.AuthContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import core.framework.api.http.HTTPStatus;
import core.framework.web.Request;
import core.framework.web.WebContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author stephen
 */
class IngestControllerTest {
    private static final String VALID_TRACE_ID = "0123456789abcdef0123456789abcdef";

    @Test
    void desktopClientTypeStampsDesktopSource() {
        assertEquals("desktop", IngestController.traceSource("desktop"));
    }

    @Test
    void absentClientTypeKeepsCliDefault() {
        // Older CLI builds send no clientType at all.
        assertEquals("cli", IngestController.traceSource(null));
        assertEquals("cli", IngestController.traceSource(""));
    }

    @Test
    void unlistedClientTypeCannotClaimOtherSources() {
        assertEquals("cli", IngestController.traceSource("chat"));
        assertEquals("cli", IngestController.traceSource("system"));
    }

    @Test
    void clientTypeIsParsedFromTheWireField() throws Exception {
        // Guards the cross-module wire contract: the CLI sends "clientType" (see HttpTraceUploader.toMap);
        // renaming the field here without a matching change would silently downgrade every desktop trace to cli.
        var request = new ObjectMapper().readValue("{\"clientType\":\"desktop\",\"spans\":[]}", IngestRequest.class);
        assertEquals("desktop", request.clientType);
    }

    @Test
    void ingestStartAuthedHasPermissionsBypassAnnotation() throws Exception {
        var method = IngestController.class.getMethod("ingestStartAuthed", Request.class);
        assertTrue(method.isAnnotationPresent(PermissionsBypass.class));
    }

    @Test
    void ingestStartAuthedFailsLoudWithoutAuthenticatedUser() {
        var controller = controllerWithUser(null);
        var request = jsonRequest("{\"traceId\":\"" + VALID_TRACE_ID + "\"}");

        assertThrows(IllegalStateException.class, () -> controller.ingestStartAuthed(request));
    }

    @Test
    void ingestStartAuthedAcceptsValidPayloadAndIgnoresUnknownFields() {
        var controller = controllerWithUser("user-1");
        when(controller.traceBuffer.offerStart(any(), eq("user-1"), eq("desktop"), anyLong())).thenReturn(Boolean.TRUE);
        var json = "{\"traceId\":\"  " + VALID_TRACE_ID + "  \",\"name\":\"  my turn  \","
            + "\"input\":\"hello\",\"clientType\":\"desktop\",\"futureField\":\"ignored\"}";

        var response = controller.ingestStartAuthed(jsonRequest(json));

        assertEquals(HTTPStatus.OK, response.status());
        var captor = ArgumentCaptor.forClass(IngestStartRequest.class);
        verify(controller.traceBuffer).offerStart(captor.capture(), eq("user-1"), eq("desktop"),
            eq((long) json.getBytes(StandardCharsets.UTF_8).length));
        assertEquals(VALID_TRACE_ID, captor.getValue().traceId);
        assertEquals("my turn", captor.getValue().name);
        assertEquals("hello", captor.getValue().input);
    }

    @Test
    void ingestStartAuthedRejectsInvalidTraceIdAndMalformedBody() {
        var controller = controllerWithUser("user-1");

        assertEquals(HTTPStatus.BAD_REQUEST, controller.ingestStartAuthed(jsonRequest("{\"traceId\":\"short123\"}")).status());
        assertEquals(HTTPStatus.BAD_REQUEST, controller.ingestStartAuthed(jsonRequest("{\"traceId\":\"" + "a".repeat(129) + "\"}")).status());
        assertEquals(HTTPStatus.BAD_REQUEST, controller.ingestStartAuthed(jsonRequest("{\"traceId\":\"invalid$chars_123456\"}")).status());
        assertEquals(HTTPStatus.BAD_REQUEST, controller.ingestStartAuthed(jsonRequest("{}")).status());
        assertEquals(HTTPStatus.BAD_REQUEST, controller.ingestStartAuthed(jsonRequest("not-json")).status());

        var emptyBodyRequest = mock(Request.class);
        when(emptyBodyRequest.body()).thenReturn(Optional.empty());
        assertEquals(HTTPStatus.BAD_REQUEST, controller.ingestStartAuthed(emptyBodyRequest).status());

        verify(controller.traceBuffer, never()).offerStart(any(), any(), any(), anyLong());
    }

    @Test
    void ingestStartAuthedTruncatesLongNameAndInputAndClampsOutOfRangeTimestamp() {
        var controller = controllerWithUser("user-1");
        when(controller.traceBuffer.offerStart(any(), eq("user-1"), eq("cli"), anyLong())).thenReturn(Boolean.TRUE);
        var longName = "n".repeat(600);
        var hugeInput = "中".repeat(1_500_000);
        long tooOld = System.currentTimeMillis() - Duration.ofDays(45).toMillis();
        var json = "{\"traceId\":\"" + VALID_TRACE_ID + "\",\"name\":\"" + longName
            + "\",\"input\":\"" + hugeInput + "\",\"startedAtEpochMs\":" + tooOld + "}";

        var response = controller.ingestStartAuthed(jsonRequest(json));

        assertEquals(HTTPStatus.OK, response.status());
        var captor = ArgumentCaptor.forClass(IngestStartRequest.class);
        verify(controller.traceBuffer).offerStart(captor.capture(), eq("user-1"), eq("cli"), anyLong());
        assertEquals(512, captor.getValue().name.length());
        assertTrue(captor.getValue().input.getBytes(StandardCharsets.UTF_8).length <= 4 * 1024 * 1024);
        assertNull(captor.getValue().startedAtEpochMs);
    }

    @Test
    void ingestStartAuthedClampsFutureTimestamp() {
        var controller = controllerWithUser("user-1");
        when(controller.traceBuffer.offerStart(any(), eq("user-1"), eq("cli"), anyLong())).thenReturn(Boolean.TRUE);
        long tooFuture = System.currentTimeMillis() + Duration.ofHours(1).toMillis();
        var json = "{\"traceId\":\"" + VALID_TRACE_ID + "\",\"startedAtEpochMs\":" + tooFuture + "}";

        var response = controller.ingestStartAuthed(jsonRequest(json));

        assertEquals(HTTPStatus.OK, response.status());
        var captor = ArgumentCaptor.forClass(IngestStartRequest.class);
        verify(controller.traceBuffer).offerStart(captor.capture(), eq("user-1"), eq("cli"), anyLong());
        assertNull(captor.getValue().startedAtEpochMs);
    }

    @Test
    void ingestStartAuthedAcceptsBoundaryTraceIdsAndPreservesInRangeTimestamp() {
        var controller = controllerWithUser("user-1");
        when(controller.traceBuffer.offerStart(any(), eq("user-1"), eq("cli"), anyLong())).thenReturn(Boolean.TRUE);
        var minTraceId = "AbCd_0123-EfGh_4";
        var maxTraceId = "z".repeat(128);
        long inRange = System.currentTimeMillis() - 1000L;

        assertEquals(HTTPStatus.OK, controller.ingestStartAuthed(
            jsonRequest("{\"traceId\":\"" + minTraceId + "\",\"startedAtEpochMs\":" + inRange + "}")).status());
        assertEquals(HTTPStatus.OK, controller.ingestStartAuthed(
            jsonRequest("{\"traceId\":\"" + maxTraceId + "\"}")).status());

        var captor = ArgumentCaptor.forClass(IngestStartRequest.class);
        verify(controller.traceBuffer, times(2))
            .offerStart(captor.capture(), eq("user-1"), eq("cli"), anyLong());
        assertEquals(minTraceId, captor.getAllValues().get(0).traceId);
        assertEquals(inRange, captor.getAllValues().get(0).startedAtEpochMs);
        assertEquals(maxTraceId, captor.getAllValues().get(1).traceId);
    }

    @Test
    void ingestStartAuthedReturns503WhenBufferIsBusy() {
        var controller = controllerWithUser("user-1");
        when(controller.traceBuffer.offerStart(any(), eq("user-1"), eq("cli"), anyLong())).thenReturn(Boolean.FALSE);

        var response = controller.ingestStartAuthed(jsonRequest("{\"traceId\":\"" + VALID_TRACE_ID + "\"}"));

        assertEquals(HTTPStatus.SERVICE_UNAVAILABLE, response.status());
    }

    private IngestController controllerWithUser(String userId) {
        var controller = new IngestController();
        controller.traceBuffer = mock(MemoryTraceSpanBuffer.class);
        controller.webContext = mock(WebContext.class);
        when(controller.webContext.get(AuthContext.USER_ID_KEY)).thenReturn(userId);
        return controller;
    }

    private Request jsonRequest(String json) {
        var request = mock(Request.class);
        when(request.body()).thenReturn(Optional.of(json.getBytes(StandardCharsets.UTF_8)));
        return request;
    }
}
