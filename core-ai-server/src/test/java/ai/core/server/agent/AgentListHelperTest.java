package ai.core.server.agent;

import ai.core.server.domain.AgentDefinition;
import ai.core.server.user.UserStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author stephen
 */
class AgentListHelperTest {
    @Test
    void assistantIsPrependedWithoutMutatingTheStoreResult() {
        var store = mock(AgentDefinitionStore.class);
        when(store.find(any(AgentDefinitionQuery.class))).thenReturn(List.of(agent("other")));
        var helper = new AgentListHelper(store, mock(UserStore.class));
        var assistant = agent("assistant:user-1");

        var result = helper.listWithAssistantFirst(new AgentDefinitionQuery(), 0, 2, assistant);

        assertEquals(List.of("assistant:user-1", "other"), result.stream().map(a -> a.id).toList());
    }

    private AgentDefinition agent(String id) {
        var agent = new AgentDefinition();
        agent.id = id;
        agent.name = id;
        return agent;
    }
}
