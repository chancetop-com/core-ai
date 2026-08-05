package ai.core.server.session;

import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.skill.MongoSkillProvider;
import ai.core.server.skill.SkillArchiveBuilder;
import ai.core.server.skill.SkillService;
import ai.core.session.InProcessAgentSession;
import ai.core.skill.SkillMetadata;
import ai.core.skill.SkillProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anySet;

class SessionSkillManagerTest {
    @Test
    void publishedNullSkillListNeverFallsBackToEditableSkills() {
        var skillService = mock(SkillService.class);
        var manager = new SessionSkillManager(skillService, mock(MongoSkillProvider.class),
            mock(SkillArchiveBuilder.class), mock(ChatMessageService.class));
        var session = mock(InProcessAgentSession.class);
        when(session.id()).thenReturn("session-1");
        var definition = new AgentDefinition();
        definition.skillIds = List.of("edited-private-skill");
        definition.publishedConfig = new AgentPublishedConfig();

        var loaded = manager.loadFrozenSkillsFromDefinition(session, definition);

        assertEquals(List.of(), loaded);
        verifyNoInteractions(skillService);
    }

    @Test
    void dynamicLoadAndUnloadAuthorizeEverySkillAgainstCaller() {
        var skillService = mock(SkillService.class);
        var provider = mock(MongoSkillProvider.class);
        when(provider.scoped(anySet())).thenReturn(mock(SkillProvider.class));
        var manager = new SessionSkillManager(skillService, provider,
                mock(SkillArchiveBuilder.class), mock(ChatMessageService.class));
        var session = mock(InProcessAgentSession.class);
        when(session.id()).thenReturn("session-1");
        var metadata = SkillMetadata.builder("seo-audit", "Audit SEO", "Admin/seo-audit")
                .namespace("Admin")
                .build();
        when(skillService.resolveAccessibleSkills(List.of("skill-1"), "caller-1"))
                .thenReturn(List.of(metadata));

        assertEquals(List.of("Admin/seo-audit"),
                manager.loadSkills(session, List.of("skill-1"), "caller-1"));
        assertEquals(List.of(), manager.unloadSkills("session-1", List.of("skill-1"), "caller-1"));

        verify(skillService, times(2))
                .resolveAccessibleSkills(List.of("skill-1"), "caller-1");
    }
}
