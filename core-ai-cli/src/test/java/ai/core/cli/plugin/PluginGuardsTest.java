package ai.core.cli.plugin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PluginGuardsTest {
    @Test
    void parsesGuardsAndMatchesExactToolNames() {
        var guards = PluginGuards.parse("""
                [{"name":"demo:no-shell","description":"deny shell","toolNames":["shell","python"]},
                 {"name":"bad name","description":"x","toolNames":["shell"]},
                 {"name":"demo:empty","description":"x","toolNames":[]}]
                """);
        assertThat(guards).hasSize(1);
        assertThat(PluginGuards.denies(guards, "shell")).isTrue();
        assertThat(PluginGuards.denies(guards, "bash")).isFalse();
    }

    @Test
    void ignoresMalformedPayloads() {
        assertThat(PluginGuards.parse("not json")).isEmpty();
        assertThat(PluginGuards.parse("{}")).isEmpty();
    }
}
