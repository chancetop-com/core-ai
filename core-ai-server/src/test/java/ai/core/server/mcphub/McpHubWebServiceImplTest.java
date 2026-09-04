package ai.core.server.mcphub;

import ai.core.api.server.mcphub.HubCallRequest;
import ai.core.api.server.mcphub.HubCallResponse;
import ai.core.api.server.mcphub.HubServersResponse;
import ai.core.server.web.auth.AuthContext;
import core.framework.web.Request;
import core.framework.web.WebContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpHubWebServiceImplTest {
    private McpHubService hubService;
    private WebContext webContext;
    private Request request;
    private McpHubWebServiceImpl webService;

    @BeforeEach
    void setUp() {
        hubService = mock(McpHubService.class);
        webContext = mock(WebContext.class);
        request = mock(Request.class);
        when(webContext.request()).thenReturn(request);
        webService = new McpHubWebServiceImpl();
        webService.hubService = hubService;
        webService.webContext = webContext;
    }

    @Test
    void serversDelegatesToHubService() {
        var expected = new HubServersResponse();
        when(hubService.servers()).thenReturn(expected);
        assertSame(expected, webService.servers());
    }

    @Test
    void callForwardsCallerIdAndClientSourceHeader() {
        when(webContext.get(AuthContext.USER_ID_KEY)).thenReturn("caller-1");
        when(request.header("X-Core-AI-Client")).thenReturn(Optional.of("cli"));
        var requestBean = new HubCallRequest();
        var expected = new HubCallResponse();
        when(hubService.call("caller-1", "cli", "jira", "create_issue", requestBean)).thenReturn(expected);

        var actual = webService.call("jira", "create_issue", requestBean);

        assertSame(expected, actual);
        verify(hubService).call("caller-1", "cli", "jira", "create_issue", requestBean);
    }

    @Test
    void callDefaultsSourceToUnknownWhenHeaderMissing() {
        when(webContext.get(AuthContext.USER_ID_KEY)).thenReturn("caller-1");
        when(request.header("X-Core-AI-Client")).thenReturn(Optional.empty());
        var requestBean = new HubCallRequest();
        var expected = new HubCallResponse();
        when(hubService.call("caller-1", "unknown", "jira", "create_issue", requestBean)).thenReturn(expected);

        var actual = webService.call("jira", "create_issue", requestBean);

        assertSame(expected, actual);
        verify(hubService).call("caller-1", "unknown", "jira", "create_issue", requestBean);
    }
}
