package ai.core.server.skill.quality;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Follows packaged Markdown links from SKILL.md and extracts the section that satisfies each requirement.
 * A short summary plus exactly one packaged Markdown link is treated as a pointer to that file; anything
 * less deterministic is left alone rather than guessed.
 *
 * @author core-ai
 */
public final class SkillDisclosureResolver {
    private static final Pattern INLINE_LINK = Pattern.compile("\\[([^\\]]*)]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)");
    private static final int MAX_DEPTH = 2;
    private static final int MAX_FILES = 20;
    private static final int SECTION_LIMIT = 8_000;
    private static final int SUMMARY_LIMIT = 300;

    static String resolvePath(String target) {
        if (target == null || target.isBlank()) return null;
        var path = target.replace('\\', '/').trim();
        while (path.startsWith("./")) path = path.substring(2);
        if (path.startsWith("#") || path.startsWith("/") || path.matches("^[A-Za-z]:/.*")) return null;
        for (var segment : path.split("/")) {
            if ("..".equals(segment)) return null;
        }
        return path;
    }

    private final SkillDocumentParser parser = new SkillDocumentParser();

    public Resolution resolve(SkillPublishCandidate candidate) {
        var docs = documents(candidate);
        var reachable = reachable(candidate, docs);
        var broken = new ArrayList<String>();
        var remote = new ArrayList<String>();
        var sections = new LinkedHashMap<String, String>();
        var sources = new LinkedHashMap<String, String>();
        var tooLarge = new LinkedHashMap<String, String>();
        for (var requirement : SkillDisclosureRequirements.ALL) {
            var resolved = resolveRequirement(candidate, docs, reachable, requirement, broken, remote);
            if (resolved == null) continue;
            if (resolved.error() != null) {
                tooLarge.put(requirement.key(), resolved.error());
                continue;
            }
            sections.put(requirement.key(), resolved.text());
            sources.put(requirement.key(), resolved.source());
        }
        return new Resolution(sections, sources, broken, remote, tooLarge, reachable);
    }

    private Map<String, SkillDocument> documents(SkillPublishCandidate candidate) {
        var docs = new LinkedHashMap<String, SkillDocument>();
        docs.put("SKILL.md", candidate.document);
        for (var entry : candidate.resources.entrySet()) {
            if (!entry.getKey().endsWith(".md")) continue;
            try {
                docs.put(entry.getKey(), parser.parse(new String(entry.getValue(), StandardCharsets.UTF_8)));
            } catch (RuntimeException e) { // NOPMD: a malformed reference becomes a broken-link issue
            }
        }
        return docs;
    }

    private Set<String> reachable(SkillPublishCandidate candidate, Map<String, SkillDocument> docs) {
        var reachable = new LinkedHashSet<String>();
        var queue = new ArrayDeque<Node>();
        queue.add(new Node("SKILL.md", 0));
        while (!queue.isEmpty() && reachable.size() < MAX_FILES) {
            var node = queue.poll();
            if (node.depth >= MAX_DEPTH) continue;
            var document = docs.get(node.path);
            if (document == null) continue;
            for (var link : document.markdownLinks()) {
                var target = resolvePath(link.target);
                if (target == null || !candidate.resources.containsKey(target)) continue;
                if (reachable.add(target)) queue.add(new Node(target, node.depth + 1));
            }
        }
        return reachable;
    }

    private Resolved resolveRequirement(SkillPublishCandidate candidate, Map<String, SkillDocument> docs,
                                        Set<String> reachable, SkillDisclosureRequirements.Requirement requirement,
                                        List<String> broken, List<String> remote) {
        var synonyms = normalized(requirement.synonyms());
        var root = docs.get("SKILL.md");
        var heading = root == null ? null : root.findHeading(synonyms);
        if (heading != null) {
            return fromSection(candidate, docs, root, heading, synonyms, broken);
        }
        var link = matchingLink(root, requirement);
        if (link != null) {
            var target = resolvePath(link.target);
            if (target != null && candidate.resources.containsKey(target)) {
                return fromFile(docs, target, synonyms);
            }
            if (isRemote(link.target)) {
                remote.add(requirement.key());
                return null;
            }
            broken.add(link.target);
            return null;
        }
        for (var path : reachable) {
            var document = docs.get(path);
            var other = document == null ? null : document.findHeading(synonyms);
            if (other != null) return fromSection(candidate, docs, document, other, synonyms, broken);
        }
        return null;
    }

    private Resolved fromSection(SkillPublishCandidate candidate, Map<String, SkillDocument> docs,
                                 SkillDocument document, SkillDocument.Heading heading, Set<String> synonyms,
                                 List<String> broken) {
        var section = document.section(heading);
        var links = linksIn(section);
        if (visibleLength(section) < SUMMARY_LIMIT && links.size() == 1) {
            var target = resolvePath(links.getFirst().target);
            if (target != null && candidate.resources.containsKey(target)) {
                return fromFile(docs, target, synonyms);
            }
            if (target != null) broken.add(target);
        }
        return new Resolved(section, "SKILL.md#" + heading.text, null);
    }

    private Resolved fromFile(Map<String, SkillDocument> docs, String path, Set<String> synonyms) {
        var document = docs.get(path);
        if (document == null) return null;
        var heading = document.findHeading(synonyms);
        if (heading != null) return new Resolved(document.section(heading), path + "#" + heading.text, null);
        if (document.body.length() > SECTION_LIMIT) {
            return new Resolved(null, path,
                path + " 没有匹配的 section，正文 " + document.body.length() + " 字符超过 8000 字符上限；请添加对应子标题或拆分文件。");
        }
        return new Resolved(document.body, path, null);
    }

    private List<SkillDocument.Link> linksIn(String section) {
        var links = new ArrayList<SkillDocument.Link>();
        var matcher = INLINE_LINK.matcher(section);
        while (matcher.find()) {
            var target = matcher.group(2);
            if (target.endsWith(".md") && !isRemote(target)) {
                links.add(new SkillDocument.Link(matcher.group(1), target, 0));
            }
        }
        return links;
    }

    private SkillDocument.Link matchingLink(SkillDocument root, SkillDisclosureRequirements.Requirement requirement) {
        if (root == null) return null;
        var names = new HashSet<String>(requirement.synonyms());
        names.add(requirement.label().toLowerCase(java.util.Locale.ROOT));
        for (var link : root.links) {
            var text = root.normalizeHeading(link.text);
            if (names.contains(text)) return link;
        }
        return null;
    }

    private Set<String> normalized(List<String> values) {
        var result = new LinkedHashSet<String>();
        for (var value : values) result.add(value.toLowerCase(java.util.Locale.ROOT));
        return result;
    }

    private int visibleLength(String section) {
        var withoutLinks = INLINE_LINK.matcher(section).replaceAll("$1");
        return withoutLinks.replaceAll("(?m)^\\s*#.*$", "").trim().length();
    }

    private boolean isRemote(String target) {
        return target.startsWith("http://") || target.startsWith("https://") || target.startsWith("mailto:");
    }

    public record Resolved(String text, String source, String error) {
    }

    public record Resolution(Map<String, String> sections, Map<String, String> sources,
                             List<String> brokenLinks, List<String> remoteRequirements,
                             Map<String, String> tooLarge, Set<String> reachableFiles) {
    }

    private record Node(String path, int depth) {
    }
}
