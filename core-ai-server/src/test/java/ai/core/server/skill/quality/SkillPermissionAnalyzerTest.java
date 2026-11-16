package ai.core.server.skill.quality;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillPermissionAnalyzerTest {
    @Test
    void detectsAttributeAndAliasChains() {
        var candidate = candidate("""
            ---
            name: demo-skill
            description: demo
            ---
            ## Workflow
            ```python
            s = session()
            s.mcp.github.search_issues(query="x")
            gh = s.mcp.github
            gh.search_issues(query="y")
            s.api["crm"]["leads"]["create"](data={})
            s.tool("generate_image")(prompt="x")
            ```
            """);
        var usages = new SkillPermissionAnalyzer().analyze(candidate);

        assertTrue(usages.stream().anyMatch(usage -> "mcp".equals(usage.kind())
            && usage.path().equals(List.of("github", "search-issues"))), usages.toString());
        assertEquals(2, usages.stream().filter(usage -> "mcp".equals(usage.kind())).count());
        assertTrue(usages.stream().anyMatch(usage -> "api-tool".equals(usage.kind())
            && usage.path().equals(List.of("crm", "leads", "create"))), usages.toString());
        assertTrue(usages.stream().anyMatch(usage -> "tool".equals(usage.kind())
            && usage.path().equals(List.of("generate-image"))));
    }

    @Test
    void marksDynamicKeysAndShellCalls() {
        var candidate = candidate("""
            ---
            name: demo-skill
            description: demo
            ---
            ## Workflow
            ```python
            s.mcp[server_name]["tool"](query="x")
            ```
            ```bash
            core-ai-cli mcp call github/search_issues --query x
            ```
            """);
        var usages = new SkillPermissionAnalyzer().analyze(candidate);

        assertTrue(usages.stream().anyMatch(usage -> "mcp".equals(usage.kind()) && usage.dynamic()));
        assertTrue(usages.stream().anyMatch(usage -> "mcp".equals(usage.kind())
            && usage.path().equals(List.of("github", "search-issues")) && !usage.dynamic()));
    }

    private SkillPublishCandidate candidate(String content) {
        Map<String, byte[]> resources = new LinkedHashMap<>();
        resources.put("README.txt", "hello".getBytes(StandardCharsets.UTF_8));
        return new SkillPublishCandidateBuilder().build("demo", "demo-skill", content, resources, null);
    }
}
