package ai.core.cli.appserver;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * @author stephen
 */
class SessionIdsTest {
    @Test
    void acceptsOpaqueSessionIds() {
        assertDoesNotThrow(() -> SessionIds.check("cli-20261030-120000-a1b2"));
        assertDoesNotThrow(() -> SessionIds.check("acp-20261030-120000-a1b2"));
        assertDoesNotThrow(() -> SessionIds.check("s1~sub~sa-1"));
    }

    @Test
    void rejectsTraversalAndForeignCharacters() {
        assertThrows(RpcException.class, () -> SessionIds.check("../x"));
        assertThrows(RpcException.class, () -> SessionIds.check("a/b"));
        assertThrows(RpcException.class, () -> SessionIds.check("a#b"));
        assertThrows(RpcException.class, () -> SessionIds.check(""));
        assertThrows(RpcException.class, () -> SessionIds.check(null));
    }
}
