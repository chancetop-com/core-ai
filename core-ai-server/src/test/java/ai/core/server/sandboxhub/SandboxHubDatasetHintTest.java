package ai.core.server.sandboxhub;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The empty dataset section has to say why it is empty, otherwise a user cannot tell a permission problem from a
 * session that was created before the dataset was bound.
 */
class SandboxHubDatasetHintTest {
    @Test
    void noHintWhenTheSessionHasBindings() {
        assertNull(SandboxHubDatasetHint.describe(true, 3));
    }

    @Test
    void asksToBindTheAgentWhenNobodyHasBindings() {
        var hint = SandboxHubDatasetHint.describe(false, 0);

        assertTrue(hint.contains("bind a dataset to the agent"));
        assertTrue(hint.contains("start a new session"));
    }

    @Test
    void pointsAtANewSessionWhenTheAgentGainedBindings() {
        var hint = SandboxHubDatasetHint.describe(false, 2);

        assertTrue(hint.contains("2 dataset binding(s)"));
        assertTrue(hint.contains("captured when a session is created"));
        assertTrue(hint.contains("start a new session"));
    }
}
