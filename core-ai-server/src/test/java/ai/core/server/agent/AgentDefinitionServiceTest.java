package ai.core.server.agent;

import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentStatus;
import ai.core.server.domain.DefinitionType;
import ai.core.server.domain.SkillDefinition;
import ai.core.server.skill.SkillService;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class AgentDefinitionServiceTest {
    @Test
    void toViewIgnoresInvalidSkillIds() {
        var service = new AgentDefinitionService();
        service.skillService = mock(SkillService.class);

        var skill = new SkillDefinition();
        skill.id = "skill-1";
        skill.name = "Skill One";
        when(service.skillService.get("skill-1")).thenReturn(skill);

        var entity = new AgentDefinition();
        entity.id = "agent-1";
        entity.name = "Agent One";
        entity.type = DefinitionType.AGENT;
        entity.status = AgentStatus.DRAFT;
        entity.skillIds = Arrays.asList(null, "", " ", "skill-1", "skill-1");
        entity.createdAt = ZonedDateTime.now();
        entity.updatedAt = entity.createdAt;

        var view = service.toView(entity);

        assertEquals(List.of("skill-1"), view.skillIds);
        assertEquals(1, view.skills.size());
        assertEquals("skill-1", view.skills.get(0).id);
        assertEquals("Skill One", view.skills.get(0).name);
        verify(service.skillService).get("skill-1");
        verifyNoMoreInteractions(service.skillService);
    }

    @Test
    void toViewMapsEnableMemory() {
        var service = new AgentDefinitionService();
        service.skillService = mock(SkillService.class);

        var entity = new AgentDefinition();
        entity.id = "agent-1";
        entity.name = "Agent One";
        entity.type = DefinitionType.AGENT;
        entity.status = AgentStatus.DRAFT;
        entity.enableMemory = false;
        entity.createdAt = ZonedDateTime.now();
        entity.updatedAt = entity.createdAt;

        var view = service.toView(entity);

        assertEquals(Boolean.FALSE, view.enableMemory);
    }

    @Test
    void prioritizeDefaultAssistantMovesAssistantToFirst() {
        var service = new AgentDefinitionService();
        var recent = agent("recent-agent");
        var assistant = agent("default-assistant");
        var old = agent("old-agent");
        var agents = new ArrayList<>(List.of(recent, assistant, old));

        service.prioritizeDefaultAssistant(agents);

        assertSame(assistant, agents.get(0));
        assertEquals(List.of(assistant, recent, old), agents);
    }

    @Test
    void prioritizeDefaultAssistantKeepsOrderWithoutAssistant() {
        var service = new AgentDefinitionService();
        var recent = agent("recent-agent");
        var old = agent("old-agent");
        var agents = new ArrayList<>(List.of(recent, old));

        service.prioritizeDefaultAssistant(agents);

        assertEquals(List.of(recent, old), agents);
    }

    private AgentDefinition agent(String id) {
        var agent = new AgentDefinition();
        agent.id = id;
        return agent;
    }
}
