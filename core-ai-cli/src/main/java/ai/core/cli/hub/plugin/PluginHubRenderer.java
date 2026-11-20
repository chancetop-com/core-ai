package ai.core.cli.hub.plugin;

import ai.core.api.server.plugin.PluginRegistryEntryView;
import ai.core.api.server.plugin.PluginRegistrySearchResponse;
import ai.core.cli.ConsoleWriter;
import ai.core.cli.command.plugins.PluginDiscovery;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.util.List;

/**
 * Human-readable rendering for the plugin hub. JSON mode is the machine contract and prints the
 * server view unchanged; text mode keeps two-space indentation and stable columns.
 *
 * @author stephen
 */
final class PluginHubRenderer {
    String searchText(PluginRegistrySearchResponse response) {
        var plugins = response == null || response.plugins == null ? List.<PluginRegistryEntryView>of() : response.plugins;
        if (plugins.isEmpty()) return "  (no plugins registered)\n";
        var sb = new StringBuilder(512);
        int idWidth = Math.min(plugins.stream().mapToInt(p -> nz(p.id).length()).max().orElse(1) + 2, 32);
        int versionWidth = Math.min(plugins.stream().mapToInt(p -> nz(p.version).length()).max().orElse(1) + 3, 16);
        for (var plugin : plugins) {
            sb.append("  ").append(pad(nz(plugin.id), idWidth))
                    .append(pad("v" + nz(plugin.version), versionWidth))
                    .append(pad(nz(plugin.vendor), 18))
                    .append(nz(plugin.name)).append('\n');
            if (plugin.description != null && !plugin.description.isBlank()) {
                sb.append("    ").append(plugin.description).append('\n');
            }
        }
        var total = response == null || response.total == null ? plugins.size() : response.total;
        sb.append("  ").append(total).append(" plugin(s)");
        if (total > plugins.size()) sb.append(", showing ").append(plugins.size());
        return sb.append('\n').toString();
    }

    @SuppressFBWarnings("VA_FORMAT_STRING_USES_NEWLINE")
    String detailText(PluginRegistryEntryView plugin) {
        var sb = new StringBuilder(1024);
        sb.append("""
                  %s v%s
                    id:          %s
                    vendor:      %s
                    description: %s
                    download:    %s
                    sha256:      %s
                    size:        %s
                    homepage:    %s
                    updated:     %s
                    required:    %s
                    optional:    %s
                    components:  %s

                  Manifest
                """.formatted(
                nz(plugin.name), nz(plugin.version), nz(plugin.id), nz(plugin.vendor), nz(plugin.description),
                nz(plugin.downloadUrl), nz(plugin.sha256), plugin.size == null ? "-" : plugin.size + " bytes",
                nz(plugin.homepage), plugin.updatedAt == null ? "-" : plugin.updatedAt,
                join(plugin.requiredCapabilities), join(plugin.optionalCapabilities), join(plugin.components)));
        for (var line : nz(plugin.manifest).split("\n", -1)) {
            sb.append("    ").append(line).append('\n');
        }
        return sb.toString();
    }

    String localText(List<PluginDiscovery.PluginEntry> plugins) {
        if (plugins.isEmpty()) return "  (no plugins installed locally)\n";
        var sb = new StringBuilder(256);
        for (var plugin : plugins) {
            sb.append("  ").append(pad(nz(plugin.name()), 32))
                    .append(pad(plugin.version() == null ? "" : "v" + plugin.version(), 12))
                    .append(plugin.path()).append('\n');
        }
        return sb.toString();
    }

    private String join(List<String> values) {
        return values == null || values.isEmpty() ? "-" : String.join(", ", values);
    }

    private String pad(String value, int width) {
        if (value.length() >= width) return value + " ";
        return value + " ".repeat(width - value.length());
    }

    private String nz(String value) {
        return value == null ? "" : value;
    }

    void print(String text) {
        ConsoleWriter.print(text.endsWith("\n") ? text : text + "\n");
    }
}
