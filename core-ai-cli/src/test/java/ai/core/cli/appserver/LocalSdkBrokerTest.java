package ai.core.cli.appserver;

import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.session.InProcessAgentSession;
import ai.core.skill.SkillCapability;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallResult;
import ai.core.tool.registry.ToolExposure;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LocalSdkBrokerTest {
    private static ToolCall tool() {
        var tool = new ToolCall() {
            @Override
            public ToolCallResult execute(String arguments) {
                return ToolCallResult.completed("menu found");
            }
        };
        tool.setName("search");
        tool.setDescription("search the menu");
        tool.setParameters(new ArrayList<>());
        tool.setExposure(ToolExposure.DIRECT);
        return tool;
    }

    @Test
    void servesMeCatalogAndCallOverTheSessionToken() throws Exception {
        var tool = tool();
        var context = ExecutionContext.builder().build();
        var agent = mock(Agent.class);
        when(agent.getName()).thenReturn("local-agent");
        when(agent.getToolCalls()).thenReturn(List.of(tool));
        when(agent.getExecutionContext()).thenReturn(context);
        when(agent.executeToolOutsideTurn(eq(tool), any(), eq(context))).thenReturn(ToolCallResult.completed("menu found"));
        var inProcess = mock(InProcessAgentSession.class);
        var session = mock(EngineSession.class);
        when(session.id()).thenReturn("local-1");
        when(session.agent()).thenReturn(agent);
        when(session.inProcess()).thenReturn(inProcess);

        try (var broker = new LocalSdkBroker(id -> id.equals("local-1") ? session : null)) {
            var base = URI.create(broker.bind("local-1"));
            var client = HttpClient.newHttpClient();

            var me = client.send(HttpRequest.newBuilder(URI.create(base + "/me")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, me.statusCode());
            assertTrue(me.body().contains("\"local-1\""));

            var catalog = client.send(HttpRequest.newBuilder(URI.create(base + "/catalog")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, catalog.statusCode());
            assertTrue(catalog.body().contains("search"));

            var call = client.send(HttpRequest.newBuilder(URI.create(base + "/tools/search/call"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"arguments\":\"{}\",\"timeout_seconds\":10}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, call.statusCode());
            assertTrue(call.body().contains("\"completed\""));
            assertTrue(call.body().contains("menu found"));
        }
    }

    @Test
    void capabilityScopeOnlyExposesDeclaredTools() throws Exception {
        var search = tool();
        var other = tool();
        other.setName("other");
        var context = ExecutionContext.builder().build();
        context.setSkillCapabilities(List.of(new SkillCapability("skill-1", "Admin/skill", "mcp",
                "mcp:analytics:run_query", "analytics/run_query", "analytics", search)));
        context.activateSkillCapabilityScope();
        var agent = mock(Agent.class);
        when(agent.getName()).thenReturn("local-agent");
        when(agent.getToolCalls()).thenReturn(List.of(search, other));
        when(agent.getExecutionContext()).thenReturn(context);
        var session = mock(EngineSession.class);
        when(session.id()).thenReturn("local-1");
        when(session.agent()).thenReturn(agent);

        try (var broker = new LocalSdkBroker(id -> session)) {
            var base = URI.create(broker.bind("local-1"));
            var catalog = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(base + "/catalog")).build(), HttpResponse.BodyHandlers.ofString());

            assertTrue(catalog.body().contains("analytics_run_query"));
            assertTrue(catalog.body().contains("analytics/run_query"));
            assertTrue(!catalog.body().contains("other"));
        }
    }

    @Test
    void rejectsAnUnknownToken() throws Exception {
        try (var broker = new LocalSdkBroker(id -> null)) {
            var base = URI.create(broker.bind("missing"));
            var response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(base + "/me")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, response.statusCode());
        }
    }
}
