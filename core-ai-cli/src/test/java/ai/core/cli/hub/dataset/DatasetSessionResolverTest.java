package ai.core.cli.hub.dataset;

import ai.core.cli.hub.HubCliError;
import ai.core.cli.hub.HubExitCodes;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * @author stephen
 */
class DatasetSessionResolverTest {
    private final DatasetSessionResolver resolver = new DatasetSessionResolver();

    @Test
    void optionWinsOverEnvironment() {
        assertEquals("from-option", resolver.resolve("from-option", Map.of("CORE_AI_SESSION_ID", "from-env")));
    }

    @Test
    void environmentIsUsedWhenOptionIsMissing() {
        assertEquals("from-env", resolver.resolve(null, Map.of("CORE_AI_SESSION_ID", "from-env")));
        assertEquals("from-env", resolver.resolve("  ", Map.of("CORE_AI_SESSION_ID", "from-env")));
    }

    @Test
    void valuesAreTrimmed() {
        assertEquals("abc", resolver.resolve(" abc ", Map.of()));
        assertEquals("abc", resolver.resolve(null, Map.of("CORE_AI_SESSION_ID", " abc\n")));
    }

    @Test
    void noSessionIsUsageErrorNotGuesswork() {
        var error = assertThrows(HubCliError.class, () -> resolver.resolve(null, Map.of()));

        assertEquals(HubExitCodes.USAGE, error.exitCode);
        assertEquals("missing session: pass --session <id> or set CORE_AI_SESSION_ID", error.getMessage());
    }
}
