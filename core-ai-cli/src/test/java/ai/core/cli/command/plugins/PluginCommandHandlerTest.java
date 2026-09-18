package ai.core.cli.command.plugins;

import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.TerminalUI;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PluginCommandHandlerTest {

    private static String renderHelp() {
        var ui = mock(TerminalUI.class);
        new PluginCommandHandler(ui).handle("help");

        var captor = ArgumentCaptor.forClass(String.class);
        verify(ui).printStreamingChunk(captor.capture());
        return captor.getValue();
    }

    private static int count(String text, String token) {
        return (text.length() - text.replace(token, "").length()) / token.length();
    }

    @Test
    void helpRendersEveryLineWithItsFormatArguments() {
        var help = renderHelp();

        assertFalse(help.contains("%"), help);
        assertTrue(help.contains("/plugins install <source> [--local|--global]"), help);
        assertTrue(help.contains("- NPM package (with optional registry)"), help);
        assertTrue(help.contains("- GitHub shorthand"), help);
        assertTrue(help.contains("/plugins install ./my-local-plugin"), help);
    }

    @Test
    void helpClosesEveryColorCode() {
        assumeTrue(AnsiTheme.isColorEnabled(), "ANSI colors are disabled by NO_COLOR");

        var help = renderHelp();

        assertEquals(count(help, AnsiTheme.MUTED) + 1, count(help, AnsiTheme.RESET), help);
    }
}
