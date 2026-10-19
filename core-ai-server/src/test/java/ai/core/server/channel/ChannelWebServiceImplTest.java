package ai.core.server.channel;

import ai.core.api.server.channel.ChannelConfigRequest;
import ai.core.server.web.auth.AuthContext;
import core.framework.web.Request;
import core.framework.web.WebContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author stephen
 */
class ChannelWebServiceImplTest {
    @Test
    void openClawChannelIgnoresRequireAuth() {
        var service = service();
        when(service.configStore.load("ocg")).thenReturn(null);

        service.create(request("ocg", "openclaw", Boolean.TRUE));

        var captor = ArgumentCaptor.forClass(ChannelConfigView.class);
        verify(service.configStore).store(captor.capture());
        assertFalse(captor.getValue().requireAuth);
    }

    @Test
    void otherChannelKeepsRequireAuth() {
        var service = service();
        when(service.configStore.load("slack")).thenReturn(null);

        service.create(request("slack", "slack", Boolean.TRUE));

        var captor = ArgumentCaptor.forClass(ChannelConfigView.class);
        verify(service.configStore).store(captor.capture());
        assertTrue(captor.getValue().requireAuth);
    }

    private ChannelWebServiceImpl service() {
        var service = new ChannelWebServiceImpl();
        service.configStore = mock(ChannelConfigStore.class);
        var webContext = mock(WebContext.class);
        when(webContext.get(AuthContext.USER_ID_KEY)).thenReturn("owner@example.com");
        var httpRequest = mock(Request.class);
        when(httpRequest.scheme()).thenReturn("https");
        when(httpRequest.header("Host")).thenReturn(Optional.of("localhost"));
        when(httpRequest.hostname()).thenReturn("localhost");
        when(webContext.request()).thenReturn(httpRequest);
        service.webContext = webContext;
        return service;
    }

    private ChannelConfigRequest request(String channelId, String channelType, Boolean requireAuth) {
        var request = new ChannelConfigRequest();
        request.channelId = channelId;
        request.channelType = channelType;
        request.requireAuth = requireAuth;
        request.agentId = "agent-1";
        return request;
    }
}
