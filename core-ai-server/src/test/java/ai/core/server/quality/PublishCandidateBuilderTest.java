package ai.core.server.quality;

import ai.core.server.domain.AgentDefinition;
import ai.core.server.systemprompt.SystemPromptService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PublishCandidateBuilderTest {
    @Test
    void systemTimeCountsOnlyWhenTheRealTemplateRendersIt() {
        var builder = new PublishCandidateBuilder();
        builder.systemPromptService = mock(SystemPromptService.class);

        assertTrue(builder.systemTimeInjected("当前时间：{{system.time}}"));
        assertFalse(builder.systemTimeInjected("当前时间：system.time"));
        assertFalse(builder.systemTimeInjected("{{#missing}}当前时间：{{system.time}}{{/missing}}"));
        assertFalse(builder.systemTimeInjected("输出日期格式 YYYY-MM-DD"));
    }

    @Test
    void managedPromptIsResolvedOnceIntoTheCandidate() {
        var builder = new PublishCandidateBuilder();
        builder.systemPromptService = mock(SystemPromptService.class);
        when(builder.systemPromptService.resolveContent("prompt-1")).thenReturn("resolved prompt");

        var entity = new AgentDefinition();
        entity.id = "agent-1";
        entity.name = "Agent";
        entity.description = "does something";
        entity.systemPromptId = "prompt-1";
        entity.definitionRevision = 3;

        var candidate = builder.build(entity);

        assertEquals("resolved prompt", candidate.config().systemPrompt);
        assertFalse(candidate.prompt().managedPrompt() && candidate.config().systemPromptId != null);
        assertEquals(3, candidate.definitionRevision());
        assertEquals("MANAGED", candidate.promptSource());
        assertTrue(candidate.executionCandidateHash().startsWith("sha256:"));
    }
}
