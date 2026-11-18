package ai.core.server.settings;

import ai.core.api.server.settings.SystemSettingsRequest;
import ai.core.server.domain.SystemSettings;
import core.framework.web.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DesktopConversationPolicyTest {
    @Test
    void inheritClearsTheOverrideAndAllOpensEveryAgent() {
        var entity = new SystemSettings();
        var request = new SystemSettingsRequest();
        request.desktopDirectConversationMode = "all";
        DesktopConversationPolicy.apply(entity, request);
        assertEquals(DesktopConversationPolicy.MODE_ALL, DesktopConversationPolicy.mode(entity));

        request.desktopDirectConversationMode = "inherit";
        DesktopConversationPolicy.apply(entity, request);
        assertEquals(DesktopConversationPolicy.MODE_INHERIT, DesktopConversationPolicy.mode(entity));
        assertNull(entity.desktopDirectConversationMode);
        assertNull(entity.desktopDirectConversationAgents);
    }

    @Test
    void selectedNormalizesAndDeduplicatesIds() {
        var entity = new SystemSettings();
        var request = new SystemSettingsRequest();
        request.desktopDirectConversationMode = "selected";
        request.desktopDirectConversationAgents = List.of(" agent-1 ", "agent-2", "agent-1", "");

        DesktopConversationPolicy.apply(entity, request);

        assertEquals(DesktopConversationPolicy.MODE_SELECTED, DesktopConversationPolicy.mode(entity));
        assertEquals(List.of("agent-1", "agent-2"), DesktopConversationPolicy.agents(entity));
    }

    @Test
    void aLegacyDocumentWithoutModeReadsAsInherit() {
        var entity = new SystemSettings();
        assertEquals(DesktopConversationPolicy.MODE_INHERIT, DesktopConversationPolicy.mode(entity));
        assertEquals(List.of(), DesktopConversationPolicy.agents(entity));
    }

    @Test
    void anUnknownModeIsRejected() {
        var request = new SystemSettingsRequest();
        request.desktopDirectConversationMode = "everything";
        assertThrows(BadRequestException.class, () -> DesktopConversationPolicy.apply(new SystemSettings(), request));
    }
}
