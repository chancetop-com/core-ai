package ai.core.skill;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Typed SDK capability declaration of a skill.
 * <p>
 * {@code allowed-tools} is the declaration source; the metadata key
 * {@value #CONTRACT_VERSION_KEY} opts the skill into using it as an SDK capability contract.
 * The manifest is parsing and normalization only: it neither grants nor resolves anything.
 *
 * @author core-ai
 */
public final class SkillSdkCapabilityManifest {
    public static final String CONTRACT_VERSION_KEY = "coreai-sdk-capabilities-version";
    public static final String CONTRACT_VERSION = "1";

    public static final String KIND_TOOL = "tool";
    public static final String KIND_MCP = "mcp";
    public static final String KIND_API_TOOL = "api-tool";
    public static final String KIND_LLM_CALL = "llm-call";
    public static final String KIND_SUBAGENT = "subagent";
    public static final String KIND_DATASET = "dataset";
    public static final String KIND_MEDIA = "media";
    public static final String KIND_DECISION = "decision";

    private static final Set<String> KNOWN_KINDS = Set.of(
            KIND_TOOL, KIND_MCP, KIND_API_TOOL, KIND_LLM_CALL, KIND_SUBAGENT, KIND_DATASET, KIND_MEDIA, KIND_DECISION
    );
    private static final Set<String> SDK_KINDS = Set.of(KIND_TOOL, KIND_MCP, KIND_API_TOOL, KIND_LLM_CALL);
    private static final Map<String, String> ALIASES = Map.of(
            "bash", "tool:code-execution",
            "read", "tool:read-file",
            "write", "tool:write-file",
            "edit", "tool:edit-file",
            "glob", "tool:glob-file",
            "grep", "tool:grep-file",
            "webfetch", "tool:web-fetch",
            "websearch", "tool:web-search"
    );

    public static SkillSdkCapabilityManifest parse(List<String> allowedTools, Map<String, String> metadata) {
        var version = metadata == null ? null : metadata.get(CONTRACT_VERSION_KEY);
        var enabled = CONTRACT_VERSION.equals(version == null ? null : version.trim());
        var entries = new ArrayList<Entry>();
        var invalid = new ArrayList<String>();
        var unsupported = new ArrayList<Entry>();
        if (!enabled) {
            return new SkillSdkCapabilityManifest(false, version, List.of(), List.of(), List.of());
        }
        if (allowedTools != null) {
            var seen = new LinkedHashSet<String>();
            for (var raw : allowedTools) {
                var normalized = normalizeAlias(raw);
                if (normalized == null) continue;
                var entry = parseEntry(normalized);
                if (entry == null) {
                    invalid.add(raw);
                } else if (!seen.add(entry.canonical())) {
                    continue;
                } else if (!entry.supported()) {
                    unsupported.add(entry);
                } else {
                    entries.add(entry);
                }
            }
        }
        return new SkillSdkCapabilityManifest(enabled, version, List.copyOf(entries), List.copyOf(invalid),
                List.copyOf(unsupported));
    }

    private static String normalizeAlias(String raw) {
        if (raw == null || raw.isBlank()) return null;
        var value = raw.trim();
        return ALIASES.getOrDefault(value.toLowerCase(java.util.Locale.ROOT), value);
    }

    private static Entry parseEntry(String raw) {
        var separator = raw.indexOf(':');
        if (separator <= 0) {
            return KIND_DECISION.equals(canonical(raw)) ? new Entry(KIND_DECISION, List.of(), raw, false) : null;
        }
        var kind = canonical(raw.substring(0, separator));
        var rest = raw.substring(separator + 1).trim();
        if (!KNOWN_KINDS.contains(kind) || rest.isEmpty()) return null;
        var parts = new ArrayList<String>();
        for (var part : rest.split("/")) {
            if (part.isBlank()) return null;
            parts.add(part.trim());
        }
        var supported = SDK_KINDS.contains(kind) && !containsWildcard(parts) && exactArity(kind, parts);
        return new Entry(kind, List.copyOf(parts), raw, supported);
    }

    // V1 resolves exact references only; group selectors stay unsupported until expansion is implemented.
    private static boolean exactArity(String kind, List<String> parts) {
        return switch (kind) {
            case KIND_TOOL -> parts.size() == 1;
            case KIND_MCP -> parts.size() == 2;
            case KIND_API_TOOL -> parts.size() == 3;
            case KIND_LLM_CALL -> parts.size() == 1;
            default -> false;
        };
    }

    private static boolean containsWildcard(List<String> parts) {
        for (var part : parts) {
            if ("*".equals(part)) return true;
        }
        return false;
    }

    private static String canonical(String value) {
        return value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private final boolean enabled;
    private final String contractVersion;
    private final List<Entry> entries;
    private final List<String> invalid;
    private final List<Entry> unsupported;

    private SkillSdkCapabilityManifest(boolean enabled, String contractVersion, List<Entry> entries,
                                        List<String> invalid, List<Entry> unsupported) {
        this.enabled = enabled;
        this.contractVersion = contractVersion;
        this.entries = entries;
        this.invalid = invalid;
        this.unsupported = unsupported;
    }

    public boolean enabled() {
        return enabled;
    }

    public String contractVersion() {
        return contractVersion;
    }

    public List<Entry> entries() {
        return entries;
    }

    public List<Entry> unsupported() {
        return unsupported;
    }

    public List<String> invalid() {
        return invalid;
    }

    public boolean isSdkCapability(String kind) {
        return SDK_KINDS.contains(kind);
    }

    /**
     * Human-readable summary appended to the skill content. It is deliberately marked as script-only:
     * the declarations are not model tools and must not be called by the model directly.
     */
    public String summary() {
        if (!enabled || (entries.isEmpty() && unsupported.isEmpty())) return "";
        var sb = new StringBuilder("\n\nSDK capabilities declared by this skill (script-only; not available as model tools):\n");
        for (var entry : entries) {
            sb.append("- ").append(entry.kind()).append(": ").append(entry.displayPath()).append('\n');
        }
        if (!unsupported.isEmpty()) {
            sb.append("\nUnsupported declarations (not granted):\n");
            for (var entry : unsupported) {
                sb.append("- ").append(entry.raw()).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * Stable digest over the supported declarations, used to bind a preview to the set a user saw.
     */
    public String digest() {
        if (!enabled) return null;
        var canonical = new ArrayList<String>(entries.size());
        for (var entry : entries) {
            canonical.add(entry.canonical());
        }
        canonical.sort(String::compareTo);
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var line : canonical) {
                digest.update(line.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            var bytes = digest.digest();
            var hex = new StringBuilder(bytes.length * 2);
            for (var b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    public record Entry(String kind, List<String> parts, String raw, boolean supported) {
        public Entry {
            parts = parts == null ? List.of() : List.copyOf(parts);
        }

        public String displayPath() {
            var separator = raw.indexOf(':');
            return separator < 0 ? raw : raw.substring(separator + 1).trim();
        }

        public String canonical() {
            return kind + ":" + String.join("/", parts);
        }
    }
}
