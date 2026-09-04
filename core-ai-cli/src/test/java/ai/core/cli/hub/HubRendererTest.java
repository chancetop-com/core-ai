package ai.core.cli.hub;

import ai.core.api.server.mcphub.HubServerView;
import ai.core.api.server.mcphub.HubToolSummary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HubRendererTest {
    private final HubRenderer renderer = new HubRenderer();

    @Test
    void serversTextAlignsColumnsAndShowsStateCounts() {
        var jira = server("jira", "Jira issue tracker", "CONNECTED", 12, false);
        var github = server("github", "GitHub automation", "FAILED", 0, true);

        var text = renderer.serversText(List.of(jira, github));

        assertTrue(text.startsWith("  jira   "), "name column padded by longest name:\n" + text);
        assertTrue(text.contains("CONNECTED"));
        assertTrue(text.contains("12 tools"));
        assertTrue(text.contains("(stale)"));
        assertTrue(text.contains("GitHub automation"));
    }

    @Test
    void searchTextShowsQualifiedNameDescriptionAndScore() {
        var hit = new HubToolSummary();
        hit.qualifiedName = "jira/create_issue";
        hit.description = "Create a Jira issue in a project";
        hit.score = 112;
        var miss = new HubToolSummary();
        miss.qualifiedName = "github/open_issue";
        miss.description = "Open a GitHub issue";
        miss.score = 0;

        var text = renderer.searchText(List.of(hit, miss));

        assertTrue(text.contains("jira/create_issue"));
        assertTrue(text.contains("(score 112)"), text);
        assertTrue(text.indexOf("jira/create_issue") < text.indexOf("github/open_issue"));
    }

    @Test
    void emptyListsHaveFriendlyPlaceholders() {
        assertEquals("  (no mcp servers visible)\n", renderer.serversText(List.of()));
        assertEquals("  (no matching tools)\n", renderer.searchText(List.of()));
    }

    @Test
    void prettyJsonIndentsNestedObjects() {
        var pretty = renderer.prettyJson("{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}}}");
        assertTrue(pretty.contains("\n"));
        assertTrue(pretty.contains("  \"properties\""));
    }

    private HubServerView server(String name, String description, String state, Integer tools, boolean stale) {
        var server = new HubServerView();
        server.name = name;
        server.description = description;
        server.state = state;
        server.toolCount = tools;
        server.stale = stale;
        return server;
    }
}
