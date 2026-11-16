package ai.core.server.skill.quality;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Typed {@code allowed-tools} declaration. It answers "is this detected use declared"; it is not a runtime
 * grant and never implies that the sandbox will provide the capability.
 *
 * @author core-ai
 */
public final class SkillPermissionDeclaration {
    private static final Set<String> MEDIA = Set.of("image", "video", "audio");
    private static final Set<String> KNOWN_NAMESPACES = Set.of(
        "tool", "mcp", "api-tool", "subagent", "llm-call", "dataset", "media"
    );
    private static final Map<String, String> MEDIA_TOOLS = Map.ofEntries(
        Map.entry("generate-image", "image"),
        Map.entry("generate-video", "video"),
        Map.entry("get-video-status", "video"),
        Map.entry("transcribe-audio", "audio"),
        Map.entry("caption-image", "image"),
        Map.entry("understand-video", "video"),
        Map.entry("decide", "decision")
    );
    private static final Map<String, String> ALIASES = Map.ofEntries(
        Map.entry("bash", "tool:code-execution"),
        Map.entry("read", "tool:read-file"),
        Map.entry("write", "tool:write-file"),
        Map.entry("edit", "tool:edit-file"),
        Map.entry("glob", "tool:glob-file"),
        Map.entry("grep", "tool:grep-file"),
        Map.entry("webfetch", "tool:web-fetch"),
        Map.entry("websearch", "tool:web-search")
    );

    public static SkillPermissionDeclaration parse(List<String> raw) {
        var entries = new ArrayList<Entry>();
        var invalid = new ArrayList<String>();
        if (raw != null) {
            var seen = new LinkedHashSet<String>();
            for (var value : raw) {
                var normalized = normalizeAlias(value);
                if (normalized == null || !seen.add(normalized)) continue;
                var entry = parseEntry(normalized);
                if (entry == null) invalid.add(value);
                else entries.add(entry);
            }
        }
        return new SkillPermissionDeclaration(List.copyOf(entries), List.copyOf(invalid));
    }

    private static String normalizeAlias(String value) {
        if (value == null || value.isBlank()) return null;
        var alias = ALIASES.get(value.trim().toLowerCase(java.util.Locale.ROOT));
        return alias == null ? value.trim() : alias;
    }

    private static Entry parseEntry(String raw) {
        var separator = raw.indexOf(':');
        if (separator <= 0) {
            if ("decision".equals(SkillCapabilityCatalog.canonical(raw))) return new Entry("decision", List.of());
            return null;
        }
        var namespace = SkillCapabilityCatalog.canonical(raw.substring(0, separator));
        var rest = raw.substring(separator + 1).trim();
        if (!KNOWN_NAMESPACES.contains(namespace) || rest.isEmpty()) return null;
        var parts = new ArrayList<String>();
        for (var part : rest.split("/")) {
            if (part.isBlank()) return null;
            parts.add(SkillCapabilityCatalog.canonical(part));
        }
        if ("media".equals(namespace)) {
            if (parts.size() != 1 || !MEDIA.contains(parts.getFirst())) return null;
            return new Entry("media", parts);
        }
        if ("decision".equals(namespace)) return null;
        return new Entry(namespace, List.copyOf(parts));
    }

    private final List<Entry> entries;
    private final List<String> invalid;

    private SkillPermissionDeclaration(List<Entry> entries, List<String> invalid) {
        this.entries = entries;
        this.invalid = invalid;
    }

    public List<String> invalid() {
        return invalid;
    }

    public boolean covers(SkillDetectedUsage usage) {
        if (usage.dynamic()) return false;
        if ("files".equals(usage.kind())) return false;
        for (var entry : entries) {
            if (matches(entry, usage)) return true;
        }
        return false;
    }

    public boolean covers(Entry entry, SkillDetectedUsage usage) {
        return !usage.dynamic() && !"files".equals(usage.kind()) && matches(entry, usage);
    }

    public boolean coversNamespace(String usageKind) {
        for (var entry : entries) {
            if (entry.parts().size() == 1 && "*".equals(entry.parts().getFirst())
                && entry.kind().equals(usageKind)) {
                return true;
            }
        }
        return false;
    }

    public List<Entry> entries() {
        return entries;
    }

    private boolean matches(Entry entry, SkillDetectedUsage usage) {
        if ("tool".equals(usage.kind())) return toolUsageMatches(entry, usage);
        if (!entry.kind().equals(usage.kind())) return false;
        var declared = entry.parts();
        if (declared.size() == 1 && "*".equals(declared.getFirst())) return true;
        return prefixMatches(declared, usage.path());
    }

    private boolean toolUsageMatches(Entry entry, SkillDetectedUsage usage) {
        if (usage.path().isEmpty()) return false;
        var name = usage.path().getFirst();
        if ("tool".equals(entry.kind())) return toolMatches(entry, name);
        if ("media".equals(entry.kind())) return entry.parts().getFirst().equals(MEDIA_TOOLS.get(name));
        if ("decision".equals(entry.kind())) return "decide".equals(name);
        return false;
    }

    private boolean toolMatches(Entry entry, String name) {
        var declared = entry.parts().getFirst();
        if (declared.equals(name)) return true;
        if (SkillCapabilityCatalog.isKnownGroup(declared)) {
            return SkillCapabilityCatalog.groupMembers(declared).contains(name);
        }
        return false;
    }

    private boolean prefixMatches(List<String> declared, List<String> used) {
        if (declared.size() > used.size()) return false;
        for (int i = 0; i < declared.size(); i++) {
            if (!declared.get(i).equals(used.get(i))) return false;
        }
        return true;
    }

    public record Entry(String kind, List<String> parts) {
        public Entry {
            parts = parts == null ? List.of() : List.copyOf(parts);
        }
    }
}
