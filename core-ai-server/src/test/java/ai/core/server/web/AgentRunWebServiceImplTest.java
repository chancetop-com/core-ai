package ai.core.server.web;

import ai.core.api.server.run.AgentCallRequest;
import ai.core.api.server.run.AgentCallResponse;
import ai.core.api.server.run.LLMCallRequest;
import ai.core.api.server.run.LLMCallResponse;
import ai.core.api.server.run.TriggerRunRequest;
import ai.core.api.server.run.TriggerRunResponse;
import ai.core.server.run.AgentRunService;
import ai.core.server.web.auth.AuthContext;
import core.framework.web.WebContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRunWebServiceImplTest {
    @Test
    void triggerPassesAuthenticatedCallerToRunService() {
        var request = new TriggerRunRequest();
        var expected = new TriggerRunResponse();
        var fixture = fixture();
        when(fixture.service.trigger("agent-1", request, "caller-1")).thenReturn(expected);

        var actual = fixture.webService.trigger("agent-1", request);

        assertSame(expected, actual);
        verify(fixture.service).trigger("agent-1", request, "caller-1");
    }

    @Test
    void callPassesAuthenticatedCallerToRunService() {
        var request = new AgentCallRequest();
        var expected = new AgentCallResponse();
        var fixture = fixture();
        when(fixture.service.call("agent-1", request, "caller-1")).thenReturn(expected);

        var actual = fixture.webService.call("agent-1", request);

        assertSame(expected, actual);
        verify(fixture.service).call("agent-1", request, "caller-1");
    }

    @Test
    void llmCallPassesAuthenticatedCallerToRunService() {
        var request = new LLMCallRequest();
        var expected = new LLMCallResponse();
        var fixture = fixture();
        when(fixture.service.llmCall("llm-1", request, "caller-1")).thenReturn(expected);

        var actual = fixture.webService.llmCall("llm-1", request);

        assertSame(expected, actual);
        verify(fixture.service).llmCall("llm-1", request, "caller-1");
    }

    private Fixture fixture() {
        var webContext = mock(WebContext.class);
        when(webContext.get(AuthContext.USER_ID_KEY)).thenReturn("caller-1");
        var service = mock(AgentRunService.class);
        var webService = new AgentRunWebServiceImpl();
        webService.webContext = webContext;
        webService.agentRunService = service;
        return new Fixture(webService, service);
    }

    private record Fixture(AgentRunWebServiceImpl webService, AgentRunService service) {
    }
}
