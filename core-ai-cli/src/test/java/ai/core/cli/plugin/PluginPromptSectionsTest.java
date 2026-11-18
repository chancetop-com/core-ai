package ai.core.cli.plugin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PluginPromptSectionsTest {
    @Test
    void parsesValidSections() {
        var sections = PluginPromptSections.parse("""
                [{"name":"p:one","title":"One","content":"first"},
                 {"name":"p:two","title":"Two","content":"second"}]
                """);
        assertThat(sections).hasSize(2);
        assertThat(sections.get(0).inject()).isEqualTo("## One\nfirst");
    }

    @Test
    void ignoresMalformedAndOversizedSections() {
        assertThat(PluginPromptSections.parse("not json")).isEmpty();
        assertThat(PluginPromptSections.parse("""
                [{"name":"p:one","title":"One","content":"%s"}]
                """.formatted("x".repeat(8001)))).isEmpty();
        assertThat(PluginPromptSections.parse("""
                [{"name":"","title":"One","content":"x"}]
                """)).isEmpty();
    }
}
