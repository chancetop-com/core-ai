package ai.core.server.share;

import core.framework.api.http.HTTPStatus;
import core.framework.log.LogAppender;
import core.framework.log.message.ActionLogMessage;
import core.framework.web.Request;
import org.junit.jupiter.api.Test;

import ai.core.server.domain.SessionShare;
import ai.core.server.domain.SessionShareFile;
import ai.core.server.web.CorsInterceptor;
import ai.core.server.web.auth.AuthInterceptor;
import core.framework.web.exception.NotFoundException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author Xander
 */
class SessionShareTransportTest {
    @Test
    void allowsAnonymousReadsWithoutMakingManagementPublic() {
        assertTrue(AuthInterceptor.isPublicPath("/api/public/session-shares/token/messages"));
        assertFalse(AuthInterceptor.isPublicPath("/api/sessions/session/shares"));
        assertFalse(AuthInterceptor.isPublicPath("/api/public/session-shares-evil/token"));
    }

    @Test
    void limitsAnonymousTrafficPerClient() {
        var interceptor = new SessionShareInterceptor(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        for (int i = 0; i < 240; i++) assertTrue(interceptor.allow("client-a"));
        assertFalse(interceptor.allow("client-a"));
        assertTrue(interceptor.allow("client-b"));
    }

    @Test
    void masksTokensInRequestDatabaseAndResponseLogs() {
        var token = "a".repeat(43);
        var message = new ActionLogMessage();
        message.context = Map.of("request_url", List.of("https://app.test/api/public/session-shares/" + token + "/messages"));
        message.traceLog = "[SQL] params=[" + token + "] response=/shared/sessions/" + token;
        message.errorMessage = "bad token " + token;
        var delegate = mock(LogAppender.class);
        new SessionShareLogAppender(delegate).append(message);
        assertFalse(message.firstContextValue("request_url").contains(token));
        assertFalse(message.traceLog.contains(token));
        assertFalse(message.errorMessage.contains(token));
        verify(delegate).append(message);
    }

    @Test
    void servesHtmlAsDownloadAndRechecksShareAfterReading() {
        var controller = new SessionShareFileController();
        controller.shareService = mock(SessionShareService.class);
        controller.fileService = mock(SessionShareFileService.class);
        var share = new SessionShare();
        var file = new SessionShareFile();
        file.contentType = "text/html";
        file.fileName = "report.html";
        var request = mock(Request.class);
        when(request.pathParam("token")).thenReturn("token");
        when(request.pathParam("refId")).thenReturn("file");
        when(controller.shareService.requirePublic("token")).thenReturn(share);
        when(controller.fileService.requireFile(share, "file")).thenReturn(file);
        when(controller.fileService.read(share, file)).thenReturn(new byte[]{1});
        var response = controller.execute(request);
        assertEquals("no-store", response.header("Cache-Control").orElseThrow());
        assertTrue(response.header("Content-Disposition").orElseThrow().startsWith("attachment"));
        assertEquals("application/octet-stream", response.contentType().orElseThrow().toString());
        verify(controller.shareService, times(2)).requirePublic("token");
    }

    @Test
    void errorsAreNotCachedAndDoNotExposeUnderlyingStorageDetails() {
        var request = mock(Request.class);
        when(request.path()).thenReturn("/api/public/session-shares/token/files/ref/content");
        var response = new CorsInterceptor().handle(request, new NotFoundException("private storage location")).orElseThrow();
        assertEquals(HTTPStatus.NOT_FOUND, response.status());
        assertEquals("no-store", response.header("Cache-Control").orElseThrow());
        assertEquals("no-referrer", response.header("Referrer-Policy").orElseThrow());
    }
}
