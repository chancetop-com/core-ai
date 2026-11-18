package ai.core.cli.plugin;

import ai.core.prompt.PromptInject;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Static prompt sections contributed by active plugins that granted prompt.sections.
 * The desktop writes them into CORE_AI_PLUGIN_PROMPT_SECTIONS; this class validates the payload
 * again and fails closed on malformed input.
 *
 * @author stephen
 */
public final class PluginPromptSections {
    public static final String ENV = "CORE_AI_PLUGIN_PROMPT_SECTIONS";

    private static final Logger LOGGER = LoggerFactory.getLogger(PluginPromptSections.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_SECTIONS = 20;
    private static final int MAX_CONTENT = 8000;
    private static final int MAX_TOTAL = 32_000;

    public static List<PromptInject> fromEnvironment() {
        if (Boolean.getBoolean("core.ai.workspace.restricted")) return List.of();
        return parse(System.getenv(ENV));
    }

    static List<PromptInject> parse(String json) {
        if (json == null || json.isBlank()) return List.of();
        var sections = new ArrayList<PromptInject>();
        int total = 0;
        try {
            var node = MAPPER.readTree(json);
            if (!node.isArray()) return List.of();
            for (var entry : node) {
                if (sections.size() >= MAX_SECTIONS || total >= MAX_TOTAL) break;
                if (!entry.isObject()) continue;
                var name = entry.path("name").asText("");
                var title = entry.path("title").asText("");
                var content = entry.path("content").asText("");
                if (name.isBlank() || title.isBlank() || content.isBlank() || content.length() > MAX_CONTENT) continue;
                total += content.length();
                sections.add(new Section(title, content));
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("ignoring invalid plugin prompt sections payload: {}", e.getMessage());
            return List.of();
        }
        return List.copyOf(sections);
    }

    private PluginPromptSections() {
    }

    private static final class Section implements PromptInject {
        private final String title;
        private final String content;

        private Section(String title, String content) {
            this.title = title;
            this.content = content;
        }

        @Override
        public String inject() {
            return "## " + title + "\n" + content;
        }
    }
}
