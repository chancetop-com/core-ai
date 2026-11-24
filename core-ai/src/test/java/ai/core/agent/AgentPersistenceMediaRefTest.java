package ai.core.agent;

import ai.core.llm.domain.Content;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.media.MediaRef;
import ai.core.session.SnapshotVersion;
import ai.core.session.UnsupportedSnapshotVersionException;
import core.framework.json.JSON;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Snapshot version 2 is the first version whose conversation state may carry a media ref instead of
 * inline bytes; a newer file must be rejected rather than silently downgraded.
 *
 * @author core-ai
 */
class AgentPersistenceMediaRefTest {
    private static final MediaRef REF = MediaRef.file("sha-1", "C:/media/sha-1.png", "image/png", "png", 1024, "sha-1")
            .displayName("photo.png")
            .source("attachment");

    @Test
    void roundTripsARefWithoutCarryingBytes() {
        var domain = AgentPersistence.AgentPersistenceDomain.of(
                List.of(Message.of(RoleType.USER, Content.of(REF))), List.of(), NodeStatus.COMPLETED);

        var json = JSON.toJSON(domain);
        var restored = JSON.fromJSON(AgentPersistence.AgentPersistenceDomain.class, json);

        assertEquals(SnapshotVersion.CURRENT, restored.version);
        var part = restored.messages.getFirst().content.getFirst();
        assertEquals(Content.ContentType.IMAGE_REF, part.type);
        assertNotNull(part.imageRef);
        assertEquals("sha-1", part.imageRef.sha256);
        assertEquals("photo.png", part.imageRef.displayName);
        assertFalse(json.contains("base64"));
    }

    @Test
    void rejectsANewerSnapshotVersion() {
        var domain = AgentPersistence.AgentPersistenceDomain.of(List.of(), List.of(), NodeStatus.COMPLETED);
        domain.version = SnapshotVersion.CURRENT + 1;
        assertThrows(UnsupportedSnapshotVersionException.class, () -> SnapshotVersion.check(domain));
    }
}
