package ai.core.cli.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class AgentSessionRunnerHelperTest {
    @TempDir
    Path tempDir;

    private Properties load(Path configFile) throws Exception {
        var props = new Properties();
        try (var in = Files.newInputStream(configFile)) {
            props.load(in);
        }
        return props;
    }

    @Test
    void persistsMaxTurnAndCreatesTheConfigFile() throws Exception {
        var config = tempDir.resolve(".core-ai").resolve("agent.properties");

        assertNull(AgentSessionRunnerHelper.persistMaxTurnAt(config, 7));

        assertTrue(Files.exists(config));
        assertEquals("7", load(config).getProperty("agent.max.turn"));
    }

    @Test
    void replacesTheExistingMaxTurnLineKeepingOtherContent() throws Exception {
        var config = tempDir.resolve("agent.properties");
        Files.writeString(config, "# user comment\nagent.max.turn=100\nlitellm.api.key=stub\n");

        assertNull(AgentSessionRunnerHelper.persistMaxTurnAt(config, 250));

        var text = Files.readString(config);
        assertFalse(text.contains("agent.max.turn=100"));
        assertEquals("250", load(config).getProperty("agent.max.turn"));
        assertEquals(1, text.lines().filter(line -> line.startsWith("agent.max.turn=")).count());
        assertTrue(text.contains("# user comment"));
        assertTrue(text.contains("litellm.api.key=stub"));
    }

    @Test
    void secondWriteUpdatesTheValueInsteadOfAppending() throws Exception {
        var config = tempDir.resolve("agent.properties");

        assertNull(AgentSessionRunnerHelper.persistMaxTurnAt(config, 3));
        assertNull(AgentSessionRunnerHelper.persistMaxTurnAt(config, 42));

        assertEquals("42", load(config).getProperty("agent.max.turn"));
        assertEquals(1, Files.readString(config).lines().filter(line -> line.startsWith("agent.max.turn=")).count());
    }
}
