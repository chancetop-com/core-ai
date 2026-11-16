package ai.core.server.skill.quality;

import core.framework.web.exception.BadRequestException;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A deliberately small Markdown reader: frontmatter, ATX headings, inline/reference links and fenced code.
 * It is not a CommonMark implementation; it is the tested subset the gate relies on, and anything outside
 * that subset is ignored rather than guessed.
 *
 * @author core-ai
 */
public final class SkillDocumentParser {
    private static final Pattern FRONTMATTER = Pattern.compile("\\A---\\s*\\r?\\n(.*?)\\r?\\n---\\s*(?:\\r?\\n|\\z)", Pattern.DOTALL);
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern INLINE_LINK = Pattern.compile("\\[([^\\]]*)]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)");
    private static final Pattern REFERENCE_DEFINITION = Pattern.compile("^\\[([^\\]]+)]:\\s*(\\S+)");
    private static final Pattern REFERENCE_LINK = Pattern.compile("\\[([^\\]]*)]\\[([^\\]]*)]");
    private static final int MAX_FRONTMATTER_CHARS = 200_000;

    public SkillDocument parse(String content) {
        if (content == null || content.isBlank()) {
            throw invalid("SKILL.md is empty");
        }
        var matcher = FRONTMATTER.matcher(content);
        if (!matcher.find()) {
            throw invalid("SKILL.md must start with YAML frontmatter delimited by ---");
        }
        var frontMatter = parseYaml(matcher.group(1));
        var body = content.substring(matcher.end());
        return scan(frontMatter, body, content.split("\n", -1).length);
    }

    private Map<String, Object> parseYaml(String yamlText) {
        if (yamlText.length() > MAX_FRONTMATTER_CHARS) {
            throw invalid("frontmatter is too large");
        }
        try {
            var options = new LoaderOptions();
            options.setCodePointLimit(MAX_FRONTMATTER_CHARS);
            Map<String, Object> data = new Yaml(new SafeConstructor(options)).load(yamlText);
            return SkillDocument.stringKeyMap(data);
        } catch (RuntimeException e) {
            throw new BadRequestException("frontmatter is not valid YAML: " + e.getMessage(),
                "SKILL_DOCUMENT_INVALID", e);
        }
    }

    private SkillDocument scan(Map<String, Object> frontMatter, String body, int lineCount) {
        var headings = new ArrayList<SkillDocument.Heading>();
        var links = new ArrayList<SkillDocument.Link>();
        var fences = new ArrayList<SkillDocument.Fence>();
        var references = new LinkedHashMap<String, String>();
        var visible = new StringBuilder(body.length());
        var lines = body.split("\n", -1);
        FenceState fence = null;
        for (int i = 0; i < lines.length; i++) {
            var line = lines[i];
            if (fence != null) {
                visible.append('\n');
                if (line.trim().startsWith(fence.marker)) {
                    fences.add(new SkillDocument.Fence(fence.language, fence.content.toString(), fence.startLine));
                    fence = null;
                } else {
                    fence.content.append(line).append('\n');
                }
                continue;
            }
            var opener = fenceOpener(line);
            if (opener != null) {
                fence = opener;
                fence.startLine = Math.max(1, i + 1);
                visible.append('\n');
                continue;
            }
            visible.append(line).append('\n');
            var heading = HEADING.matcher(line);
            if (heading.matches()) {
                headings.add(new SkillDocument.Heading(heading.group(1).length(), heading.group(2).trim(), i + 1));
            }
            var definition = REFERENCE_DEFINITION.matcher(line.trim());
            if (definition.find()) {
                references.put(definition.group(1).trim().toLowerCase(java.util.Locale.ROOT), definition.group(2).trim());
            }
            collectInlineLinks(line, i + 1, links);
        }
        collectReferenceLinks(visible.toString(), references, links);
        return new SkillDocument(frontMatter, body, headings, links, fences, lineCount);
    }

    private void collectInlineLinks(String line, int lineNumber, List<SkillDocument.Link> links) {
        var matcher = INLINE_LINK.matcher(line);
        while (matcher.find()) {
            links.add(new SkillDocument.Link(matcher.group(1).trim(), matcher.group(2).trim(), lineNumber));
        }
    }

    private void collectReferenceLinks(String body, Map<String, String> references, List<SkillDocument.Link> links) {
        if (references.isEmpty()) return;
        var matcher = REFERENCE_LINK.matcher(body);
        while (matcher.find()) {
            var key = matcher.group(2).isBlank() ? matcher.group(1) : matcher.group(2);
            var target = references.get(key.trim().toLowerCase(java.util.Locale.ROOT));
            if (target != null) {
                links.add(new SkillDocument.Link(matcher.group(1).trim(), target, lineOf(body, matcher.start())));
            }
        }
    }

    private int lineOf(String body, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < body.length(); i++) {
            if (body.charAt(i) == '\n') line++;
        }
        return line;
    }

    private FenceState fenceOpener(String line) {
        var trimmed = line.trim();
        if (!trimmed.startsWith("```") && !trimmed.startsWith("~~~")) return null;
        var marker = trimmed.startsWith("```") ? "```" : "~~~";
        if (trimmed.length() < marker.length()) return null;
        var state = new FenceState();
        state.marker = marker;
        state.language = trimmed.substring(marker.length()).trim().toLowerCase(java.util.Locale.ROOT);
        return state;
    }

    private BadRequestException invalid(String message) {
        return new BadRequestException(message, "SKILL_DOCUMENT_INVALID");
    }

    private static final class FenceState {
        private String marker;
        private String language;
        private int startLine;
        private final StringBuilder content = new StringBuilder();
    }
}
