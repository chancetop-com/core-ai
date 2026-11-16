package ai.core.server.skill.quality;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parsed SKILL.md: frontmatter plus the body structure the publish gate needs. Fenced code is excluded
 * from link and heading scanning by the parser, so a documented example cannot satisfy a requirement.
 *
 * @author core-ai
 */
public final class SkillDocument {
    static Map<String, Object> stringKeyMap(Map<?, ?> raw) {
        var result = new LinkedHashMap<String, Object>();
        if (raw == null) return result;
        raw.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    public final Map<String, Object> frontMatter;
    public final String body;
    public final List<Heading> headings;
    public final List<Link> links;
    public final List<Fence> fences;
    public final int lineCount;

    SkillDocument(Map<String, Object> frontMatter, String body, List<Heading> headings,
                  List<Link> links, List<Fence> fences, int lineCount) {
        this.frontMatter = frontMatter;
        this.body = body;
        this.headings = headings;
        this.links = links;
        this.fences = fences;
        this.lineCount = lineCount;
    }

    /** Section text from the heading to the next heading of the same or a higher level. */
    public String section(Heading heading) {
        var lines = body.split("\n", -1);
        var end = lines.length;
        for (var other : headings) {
            if (other.line <= heading.line || other.level > heading.level) continue;
            end = Math.min(end, other.line - 1);
        }
        var text = new StringBuilder();
        for (int i = heading.line; i < end; i++) {
            if (i > heading.line) text.append('\n');
            text.append(lines[i]);
        }
        return text.toString();
    }

    public String normalizeHeading(String text) {
        var value = text == null ? "" : text.trim().toLowerCase(java.util.Locale.ROOT);
        value = value.replaceAll("^[#>*_`\\s]+", "").replaceAll("[#*_`\\s]+$", "");
        value = value.replaceAll("^(§\\s*)?(step\\s+)?\\d+([.)]\\s*|\\s+-\\s+)", "");
        value = value.replaceAll("^\\d+(\\.\\d+)*[.)]?\\s+", "");
        return value.replaceAll("\\s+", " ").trim();
    }

    public Heading findHeading(java.util.Collection<String> synonyms) {
        for (var heading : headings) {
            if (synonyms.contains(normalizeHeading(heading.text))) return heading;
        }
        return null;
    }

    public List<Link> markdownLinks() {
        var result = new ArrayList<Link>();
        for (var link : links) {
            if (link.target == null || link.target.isBlank()) continue;
            if (link.target.startsWith("#") || link.target.startsWith("http://") || link.target.startsWith("https://")) continue;
            if (link.target.endsWith(".md")) result.add(link);
        }
        return result;
    }

    public static final class Heading {
        public final int level;
        public final String text;
        public final int line;

        Heading(int level, String text, int line) {
            this.level = level;
            this.text = text;
            this.line = line;
        }
    }

    public static final class Link {
        public final String text;
        public final String target;
        public final int line;

        Link(String text, String target, int line) {
            this.text = text;
            this.target = target;
            this.line = line;
        }
    }

    public static final class Fence {
        public final String language;
        public final String content;
        public final int line;

        Fence(String language, String content, int line) {
            this.language = language;
            this.content = content;
            this.line = line;
        }
    }
}
