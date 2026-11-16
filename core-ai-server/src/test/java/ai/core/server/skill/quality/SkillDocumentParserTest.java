package ai.core.server.skill.quality;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillDocumentParserTest {
    @Test
    void parsesFrontMatterHeadingsAndLinksAndIgnoresFencedLinks() {
        var content = """
            ---
            name: demo-skill
            description: "Do demo things. Use when asked. Scope: demo only."
            metadata:
              version: 1.0.0
            ---
            # Demo

            ## Workflow
            See [details](references/details.md).

            ```python
            # [not-a-link](missing.md)
            print("hello")
            ```

            [reference][ref]

            [ref]: references/other.md
            """;
        var document = new SkillDocumentParser().parse(content);

        assertEquals("demo-skill", document.frontMatter.get("name"));
        assertEquals(2, document.headings.size());
        assertEquals("Workflow", document.headings.get(1).text);
        assertTrue(document.links.stream().anyMatch(link -> "references/details.md".equals(link.target)));
        assertTrue(document.links.stream().anyMatch(link -> "references/other.md".equals(link.target)));
        assertFalse(document.links.stream().anyMatch(link -> "missing.md".equals(link.target)));
        assertEquals(1, document.fences.size());
        assertEquals("python", document.fences.getFirst().language);
    }

    @Test
    void normalizesNumberedHeadings() {
        var document = new SkillDocumentParser().parse("""
            ---
            name: demo-skill
            description: demo
            ---
            ## 1. Workflow
            step
            ## Quality Assurance (QA)
            check
            """);

        assertEquals("workflow", document.normalizeHeading(document.headings.getFirst().text));
    }
}
