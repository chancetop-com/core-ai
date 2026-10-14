package ai.core.cli.helper;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tool naming: slug, non-ASCII fallback and collision renaming.
 *
 * @author stephen
 */
class HelperAgentToolProviderTest {
    @Test
    void slugKeepsAsciiAndCollapsesSeparators() {
        assertEquals("hr", HelperAgentToolProvider.slug("HR 助手"));
        assertEquals("code_reviewer", HelperAgentToolProvider.slug("Code Reviewer"));
        assertEquals("a1_b2", HelperAgentToolProvider.slug("a1 -- b2"));
    }

    @Test
    void slugOfNonAsciiNameIsEmpty() {
        assertEquals("", HelperAgentToolProvider.slug("人力资源助手"));
    }

    @Test
    void nonAsciiNameFallsBackToIdSuffix() {
        var entry = entry("abcdef1234567890", "人力资源助手");

        assertEquals("helper_abcdef12", HelperAgentToolProvider.uniqueName(entry, new HashSet<>()));
    }

    @Test
    void collisionWithReservedNameRenames() {
        var entry = entry("abcdef1234567890", "HR 助手");
        Set<String> taken = new HashSet<>(List.of("hr"));

        assertEquals("hr_abcdef12", HelperAgentToolProvider.uniqueName(entry, taken));
    }

    @Test
    void collisionBetweenHelpersRenamesSecond() {
        var taken = new HashSet<String>();
        HelperAgentToolProvider.uniqueName(entry("aaaaaaaaaaaaaaaa", "HR 助手"), taken);

        assertEquals("hr_bbbbbbbb", HelperAgentToolProvider.uniqueName(entry("bbbbbbbbbbbbbbbb", "HR 助手"), taken));
    }

    @Test
    void disabledHelpersAreNotMounted() {
        var disabled = entry("a1", "HR 助手");
        disabled.enabled = Boolean.FALSE;

        var provider = HelperAgentToolProvider.create(List.of(disabled), Set.of(), () -> null, () -> null);

        assertTrue(provider.provide().isEmpty());
    }

    @Test
    void enabledHelpersBecomeToolsWithDescription() {
        var provider = HelperAgentToolProvider.create(List.of(entry("abcdef1234567890", "HR 助手")), Set.of(), () -> null, () -> null);

        assertEquals(1, provider.provide().size());
        var tool = provider.provide().get("hr");
        assertFalse(tool.getDescription().isBlank());
        assertTrue(tool.getDescription().contains("HR 助手"));
    }

    private HelperEntry entry(String agentId, String name) {
        var entry = new HelperEntry();
        entry.agentId = agentId;
        entry.name = name;
        entry.enabled = Boolean.TRUE;
        return entry;
    }
}
