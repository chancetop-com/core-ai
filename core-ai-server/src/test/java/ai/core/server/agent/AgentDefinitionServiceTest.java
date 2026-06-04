package ai.core.server.agent;

import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentStatus;
import ai.core.server.domain.DefinitionType;
import ai.core.server.domain.SkillDefinition;
import ai.core.server.skill.SkillService;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
