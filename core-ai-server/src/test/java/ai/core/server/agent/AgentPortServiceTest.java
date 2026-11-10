package ai.core.server.agent;

import ai.core.api.server.agent.AgentDefinitionView;
import ai.core.api.server.agent.CreateAgentRequest;
import ai.core.api.server.agent.ExportAgentResponse;
import ai.core.api.server.agent.ImportAgentResponse;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.DefinitionType;
import ai.core.server.domain.SkillDefinition;
import ai.core.server.skill.SkillService;
import ai.core.server.systemprompt.SystemPromptService;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.BadRequestException;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentPortServiceTest {
    private static Fixture service() {
        var fixture = new Fixture();
        fixture.port.definitionService = fixture.definitionService;
        fixture.port.skillService = fixture.skillService;
        fixture.port.systemPromptService = fixture.systemPromptService;
        fixture.port.agentDefinitionCollection = fixture.collection;
        when(fixture.collection.findOne(any(Bson.class))).thenReturn(Optional.empty());
        return fixture;
    }

    private static SkillDefinition skill(String id, String namespace, String name) {
        var skill = new SkillDefinition();
        skill.id = id;
        skill.namespace = namespace;
        skill.name = name;
        skill.qualifiedName = namespace + "/" + name;
        return skill;
    }

    @Test
    void parseEnvelopeAcceptsLegacyFileWithoutFormat() {
        ExportAgentResponse envelope = AgentPortService.parseEnvelope("""
            {"name": "legacy", "system_prompt": "hi"}
            """);

        assertEquals("legacy", envelope.name);
        assertEquals("hi", envelope.systemPrompt);
        assertNull(envelope.format);
    }

    @Test
    void parseEnvelopeRejectsUnknownFormat() {
        assertThrows(BadRequestException.class, () -> AgentPortService.parseEnvelope("""
            {"format": "something-else", "name": "x"}
            """));
    }

    @Test
    void parseEnvelopeRejectsMissingName() {
        assertThrows(BadRequestException.class, () -> AgentPortService.parseEnvelope("""
            {"format": "core-ai-agent-export/v1"}
            """));
    }

    @Test
    void exportResolvesManagedSystemPrompt() {
        var service = service();
        var agent = new AgentDefinition();
        agent.id = "agent-1";
        agent.name = "managed";
        agent.type = DefinitionType.AGENT;
        agent.systemPromptId = "prompt-1";
        when(service.definitionService.getEntity("agent-1")).thenReturn(agent);
        when(service.systemPromptService.resolveContent("prompt-1")).thenReturn("resolved prompt");

        ExportAgentResponse envelope = service.port.export("agent-1");

        assertEquals("resolved prompt", envelope.systemPrompt);
        assertEquals("core-ai-agent-export/v1", envelope.format);
    }

    @Test
    void importResolvesSkillByQualifiedName() {
        var service = service();
        var prodSkill = skill("prod-skill", "docs", "pdf-helper");
        when(service.skillService.findOrNull("uat-skill")).thenReturn(null);
        when(service.skillService.findByQualifiedNameOrNull("docs/pdf-helper")).thenReturn(prodSkill);
        when(service.definitionService.create(any(), eq("user-1"))).thenReturn(new AgentDefinitionView());

        ImportAgentResponse response = service.port.importAgent("""
            {"name": "portable", "skills": [{"id": "uat-skill", "qualified_name": "docs/pdf-helper", "name": "pdf-helper"}]}
            """, null, false, "user-1");

        ArgumentCaptor<CreateAgentRequest> captor = ArgumentCaptor.forClass(CreateAgentRequest.class);
        verify(service.definitionService).create(captor.capture(), eq("user-1"));
        assertEquals(List.of("prod-skill"), captor.getValue().skillIds);
        assertTrue(response.unresolvedReferences.isEmpty());
    }

    @Test
    void importDropsUnavailableSkillAndReportsIt() {
        var service = service();
        when(service.skillService.findOrNull(any())).thenReturn(null);
        when(service.skillService.findByQualifiedNameOrNull(any())).thenReturn(null);
        when(service.definitionService.create(any(), eq("user-1"))).thenReturn(new AgentDefinitionView());

        ImportAgentResponse response = service.port.importAgent("""
            {"name": "portable", "skill_ids": ["uat-skill"]}
            """, null, false, "user-1");

        ArgumentCaptor<CreateAgentRequest> captor = ArgumentCaptor.forClass(CreateAgentRequest.class);
        verify(service.definitionService).create(captor.capture(), eq("user-1"));
        assertNull(captor.getValue().skillIds);
        assertEquals(1, response.unresolvedReferences.size());
        assertEquals("SKILL", response.unresolvedReferences.get(0).refType);
        assertEquals("uat-skill", response.unresolvedReferences.get(0).refId);
    }

    @Test
    void importKeepsToolsAndScalarConfig() {
        var service = service();
        when(service.definitionService.create(any(), eq("user-1"))).thenReturn(new AgentDefinitionView());

        ImportAgentResponse response = service.port.importAgent("""
            {"name": "portable", "model": "gpt-4.1", "temperature": 0.3,
             "tools": [{"id": "builtin-web-search", "type": "BUILTIN"}], "enable_memory": true}
            """, null, false, "user-1");

        ArgumentCaptor<CreateAgentRequest> captor = ArgumentCaptor.forClass(CreateAgentRequest.class);
        verify(service.definitionService).create(captor.capture(), eq("user-1"));
        assertEquals("gpt-4.1", captor.getValue().model);
        assertEquals(0.3, captor.getValue().temperature, 0.0001);
        assertTrue(captor.getValue().enableMemory);
        assertEquals(1, captor.getValue().tools.size());
        assertEquals("builtin-web-search", captor.getValue().tools.get(0).id);
        assertEquals("portable", response.config.name);
    }

    @Test
    void previewDoesNotCreateAgent() {
        var service = service();

        ImportAgentResponse response = service.port.importAgent("""
            {"name": "portable", "model": "gpt-4.1"}
            """, null, true, "user-1");

        assertNull(response.agent);
        assertEquals("portable", response.config.name);
        verify(service.definitionService, never()).create(any(), any());
    }

    private static final class Fixture {
        final AgentPortService port = new AgentPortService();
        final AgentDefinitionService definitionService = mock(AgentDefinitionService.class);
        final SkillService skillService = mock(SkillService.class);
        final SystemPromptService systemPromptService = mock(SystemPromptService.class);
        @SuppressWarnings("unchecked")
        final MongoCollection<AgentDefinition> collection = mock(MongoCollection.class);
    }
}
