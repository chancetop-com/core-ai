package ai.core.server.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class ApplicationMcpManagerTest {
    @Test
    void createsAndOwnsAnApplicationScopedManager() {
        var applicationManager = new ApplicationMcpManager();

        var first = applicationManager.getOrCreate();
        var second = applicationManager.getOrCreate();

        assertSame(first, second);
        assertTrue(applicationManager.isInitialized());

        applicationManager.shutdown();

        assertFalse(applicationManager.isInitialized());
    }
}
