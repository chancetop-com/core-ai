package ai.core.cli.hub;

import ai.core.api.server.mcphub.HubServerMatch;
import ai.core.api.server.mcphub.HubServerView;
import ai.core.api.server.mcphub.HubToolSummary;
import ai.core.api.server.mcphub.HubToolsResponse;
import ai.core.api.server.skillhub.SkillHubNamespaceMatch;
import ai.core.api.server.skillhub.SkillHubSearchResponse;
import ai.core.api.server.skillhub.SkillHubSummary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void searchTextShowsBrandServersLineThenTools() {
        var gbp = serverMatch("google-gbp", 23, 50);
        var dataforseo = serverMatch("dataforseo", 25, 0);
        var tools = List.of(
                tool("google-gbp/reviews", "List reviews of a location"),
                tool("dataforseo/google_keywords", "Google keyword data"),
                tool("google-gbp/media", "List media of a location"),
                tool("dataforseo/google_serps", "Google SERP data"),
                tool("google-gbp/posts", "List local posts"),
                tool("dataforseo/google_trends", "Google trends data"));

        var text = renderer.searchText(response(List.of(gbp, dataforseo), tools));

        assertTrue(text.startsWith("Servers (1): google-gbp(23)\n"), "only brand-matched servers listed:\n" + text);
        assertTrue(text.contains("Tools:\n"));
        assertTrue(text.indexOf("google-gbp/reviews") < text.indexOf("dataforseo/google_keywords"));
        assertFalse(text.contains("(score "), "text output carries no scores");
        assertTrue(text.contains("(+20 more, --on-server google-gbp)"), "more-note on last google-gbp line:\n" + text);
        assertTrue(text.contains("(+22 more, --on-server dataforseo)"), "more-note on last dataforseo line:\n" + text);
    }

    @Test
    void searchTextWithoutBrandServersIsPlainToolList() {
        var dataforseo = serverMatch("dataforseo", 25, 0);
        var tools = List.of(tool("dataforseo/google_keywords", "Google keyword data"));

        var text = renderer.searchText(response(List.of(dataforseo), tools));

        assertFalse(text.contains("Servers ("), "no servers line without brand matches:\n" + text);
        assertFalse(text.contains("Tools:"));
        assertTrue(text.contains("dataforseo/google_keywords"));
        assertTrue(text.contains("(+24 more, --on-server dataforseo)"));
    }

    @Test
    void emptyListsHaveFriendlyPlaceholders() {
        assertEquals("  (no mcp servers visible)\n", renderer.serversText(List.of()));
        assertEquals("  (no matching tools)\n", renderer.searchText(new HubToolsResponse()));
        assertEquals("  (no matching tools)\n", renderer.searchText(response(List.of(), List.of())));
    }

    @Test
    void prettyJsonIndentsNestedObjects() {
        var pretty = renderer.prettyJson("{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}}}");
        assertTrue(pretty.contains("\n"));
        assertTrue(pretty.contains("  \"properties\""));
    }

    @Test
    void skillSearchTextUsesNamespaceGroups() {
        var stephen = namespaceMatch("stephen", 3, 50);
        var anthropics = namespaceMatch("anthropics", 1, 0);
        var skills = List.of(
                skill("stephen/code-review", "Structured code review"),
                skill("stephen/pr-review", "Review a GitHub PR"),
                skill("anthropics/code-review", "Anthropic review style"));

        var text = renderer.skillSearchText(skillResponse(List.of(stephen, anthropics), skills));

        assertTrue(text.startsWith("Namespaces (1): stephen(3)\n"), "brand namespaces line:\n" + text);
        assertTrue(text.contains("Skills:\n"));
        assertTrue(text.contains("(+1 more, --namespace stephen)"), "more-note on the last stephen line:\n" + text);
        assertTrue(text.indexOf("stephen/code-review") < text.indexOf("anthropics/code-review"));
    }

    @Test
    void skillSearchTextWithoutBrandIsPlainList() {
        var text = renderer.skillSearchText(skillResponse(List.of(namespaceMatch("anthropics", 1, 0)),
                List.of(skill("anthropics/code-review", "Anthropic review style"))));
        assertFalse(text.contains("Namespaces ("));
        assertTrue(text.contains("anthropics/code-review"));
        assertEquals("  (no matching skills)\n", renderer.skillSearchText(new SkillHubSearchResponse()));
    }

    private SkillHubSearchResponse skillResponse(List<SkillHubNamespaceMatch> namespaces, List<SkillHubSummary> skills) {
        var response = new SkillHubSearchResponse();
        response.namespaces = namespaces;
        response.skills = skills;
        return response;
    }

    private SkillHubNamespaceMatch namespaceMatch(String namespace, Integer matched, Integer score) {
        var match = new SkillHubNamespaceMatch();
        match.namespace = namespace;
        match.matchedCount = matched;
        match.score = score;
        return match;
    }

    private SkillHubSummary skill(String qualifiedName, String description) {
        var skill = new SkillHubSummary();
        skill.qualifiedName = qualifiedName;
        skill.namespace = qualifiedName.substring(0, qualifiedName.indexOf('/'));
        skill.name = qualifiedName.substring(qualifiedName.indexOf('/') + 1);
        skill.description = description;
        return skill;
    }

    private HubToolsResponse response(List<HubServerMatch> servers, List<HubToolSummary> tools) {
        var response = new HubToolsResponse();
        response.servers = servers;
        response.tools = tools;
        return response;
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

    private HubServerMatch serverMatch(String name, Integer matched, Integer score) {
        var server = new HubServerMatch();
        server.name = name;
        server.matchedCount = matched;
        server.score = score;
        return server;
    }

    private HubToolSummary tool(String qualifiedName, String description) {
        var tool = new HubToolSummary();
        tool.qualifiedName = qualifiedName;
        tool.server = qualifiedName.substring(0, qualifiedName.indexOf('/'));
        tool.name = qualifiedName.substring(qualifiedName.indexOf('/') + 1);
        tool.description = description;
        return tool;
    }
}
