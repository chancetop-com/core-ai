package ai.core.server.selfharness;

import ai.core.api.server.agent.CreateAgentRequest;
import ai.core.api.server.agent.ListAgentsRequest;
import ai.core.api.server.dataset.ListDatasetsRequest;
import ai.core.api.server.skill.ListSkillsRequest;
import core.framework.json.JSON;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SelfHarnessRequestBinderTest {
    @Test
    void bindsQueryParamNamesThatPlainJsonBindingSilentlyDrops() {
        var request = SelfHarnessRequestBinder.bind(ListSkillsRequest.class,
                "{\"q\":\"gpt-image\",\"search_in\":\"name\",\"source_type\":\"REPO\",\"namespace\":\"wuyoscar\",\"offset\":5,\"limit\":3}");

        assertEquals("gpt-image", request.query);
        assertEquals("name", request.searchIn);
        assertEquals("REPO", request.sourceType);
        assertEquals("wuyoscar", request.namespace);
        assertEquals(5, request.offset);
        assertEquals(3, request.limit);
    }

    @Test
    void bindsAgentAndDatasetFilters() {
        var agents = SelfHarnessRequestBinder.bind(ListAgentsRequest.class,
                "{\"my\":\"true\",\"include_system_default\":true,\"query\":\"builder\"}");
        assertEquals("true", agents.myAgents);
        assertEquals(Boolean.TRUE, agents.includeSystemDefault);
        assertEquals("builder", agents.query);

        var datasets = SelfHarnessRequestBinder.bind(ListDatasetsRequest.class, "{\"q\":\"talent\",\"limit\":10}");
        assertEquals("talent", datasets.query);
        assertEquals(10, datasets.limit);
    }

    @Test
    void keepsPropertyNamesAndIgnoresUnknownArguments() {
        var request = SelfHarnessRequestBinder.bind(CreateAgentRequest.class,
                "{\"name\":\"helper\",\"system_prompt\":\"be nice\",\"max_turns\":5,\"skill_ids\":[\"a\",\"b\"],\"id\":\"not-a-field\",\"unknown\":1}");

        assertEquals("helper", request.name);
        assertEquals("be nice", request.systemPrompt);
        assertEquals(5, request.maxTurns);
        assertEquals(List.of("a", "b"), request.skillIds);
    }

    @Test
    void absentArgumentsStayNull() {
        var request = SelfHarnessRequestBinder.bind(ListSkillsRequest.class, "{}");

        assertNull(request.query);
        assertNull(request.searchIn);
        assertNull(request.limit);
    }

    // documents the core-ng behavior the binder exists for: plain JSON binding only understands
    // @Property / field names, so query-param names (q, search_in) are silently dropped
    @Test
    void plainJsonBindingDropsQueryParamNames() {
        var request = JSON.fromJSON(ListSkillsRequest.class, "{\"q\":\"gpt-image\",\"search_in\":\"name\",\"offset\":5}");

        assertNull(request.query);
        assertNull(request.searchIn);
        assertEquals(5, request.offset);
    }
}
