package ai.core.cli.agent;

import ai.core.bootstrap.PropertiesFileSource;
import org.junit.jupiter.api.Test;

import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class CliToolPolicyTest {
    @Test
    void noPropertyMeansNothingDisabled() {
        assertTrue(CliToolPolicy.disabledTools(new PropertiesFileSource(new Properties())).isEmpty());
    }

    @Test
    void parsesCommaSeparatedNamesCaseInsensitivelyAndTrimmed() {
        var props = new Properties();
        props.setProperty(CliToolPolicy.DISABLED_TOOLS_PROPERTY, " Web_Fetch , web_search ,,");

        var disabled = CliToolPolicy.disabledTools(new PropertiesFileSource(props));

        assertEquals(Set.of("web_fetch", "web_search"), disabled);
    }

    @Test
    void expandsBuiltinToolSetIds() {
        assertEquals(Set.of("web_fetch", "web_search"), CliToolPolicy.parse("builtin-web"));
    }

    @Test
    void mixesToolNamesAndBuiltinToolSetIds() {
        assertEquals(Set.of("web_fetch", "web_search", "shell_command"),
                CliToolPolicy.parse("builtin-web,shell_command"));
    }
}
