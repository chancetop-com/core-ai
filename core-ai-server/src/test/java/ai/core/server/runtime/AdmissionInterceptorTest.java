package ai.core.server.runtime;

import core.framework.http.HTTPMethod;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class AdmissionInterceptorTest {
    @Test
    void guardsOnlyTheCallsThatCreateWork() {
        assertTrue(AdmissionInterceptor.guarded(HTTPMethod.POST, "/api/sessions"));
        assertTrue(AdmissionInterceptor.guarded(HTTPMethod.POST, "/api/sessions/messages/stream"));
        assertTrue(AdmissionInterceptor.guarded(HTTPMethod.POST, "/api/sessions/session-1/messages"));
        assertTrue(AdmissionInterceptor.guarded(HTTPMethod.PUT, "/api/sessions/events"));
        assertTrue(AdmissionInterceptor.guarded(HTTPMethod.POST, "/api/runs"));
        assertTrue(AdmissionInterceptor.guarded(HTTPMethod.POST, "/api/channels/slack/events"));
        assertTrue(AdmissionInterceptor.guarded(HTTPMethod.POST, "/api/gateway/v1/chat/completions"));
    }

    @Test
    void everyChatEntryPointIsClassifiedAsTheSameScope() {
        assertEquals("chat_turn",
                AdmissionInterceptor.workRoute(HTTPMethod.POST, "/api/sessions/messages/stream").scope());
        assertEquals("chat_turn",
                AdmissionInterceptor.workRoute(HTTPMethod.POST, "/api/sessions/session-1/messages").scope());
        assertEquals("chat_turn", AdmissionInterceptor.workRoute(HTTPMethod.PUT, "/api/sessions/events").scope());
    }

    @Test
    void leavesReadsAdminAndInfrastructurePathsAlone() {
        assertFalse(AdmissionInterceptor.guarded(HTTPMethod.GET, "/api/sessions"));
        assertFalse(AdmissionInterceptor.guarded(HTTPMethod.GET, "/api/runs"));
        assertFalse(AdmissionInterceptor.guarded(HTTPMethod.GET, "/api/admin/runtime/stats"));
        assertFalse(AdmissionInterceptor.guarded(HTTPMethod.POST, "/api/admin/runtime/limits"));
        assertFalse(AdmissionInterceptor.guarded(HTTPMethod.GET, "/health-check"));
        assertFalse(AdmissionInterceptor.guarded(HTTPMethod.GET, "/api/files/f1/content"));
        assertFalse(AdmissionInterceptor.guarded(HTTPMethod.PUT, "/api/sessions/messages/stream"));
        assertFalse(AdmissionInterceptor.guarded(HTTPMethod.POST, "/api/session-history"));
        assertFalse(AdmissionInterceptor.guarded(HTTPMethod.POST, null));
    }
}
