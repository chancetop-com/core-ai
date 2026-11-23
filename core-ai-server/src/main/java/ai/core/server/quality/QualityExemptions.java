package ai.core.server.quality;

import ai.core.server.domain.AgentDefinition;
import core.framework.web.exception.BadRequestException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Admin-managed whitelist for subjects that are known to violate a quality rule but cannot or must not be
 * changed (external skills, personal assistant copies). Exemptions are stored inside the immutable policy
 * revision, so applying one is audited exactly like a rule change. Built-in agents are exempt without an
 * entry because every user gets a personal copy that cannot be listed one by one.
 *
 * @author core-ai
 */
public final class QualityExemptions {
    public static final int MAX_ENTRIES = 500;
    public static final int MAX_ENTRY_LENGTH = 200;
    public static final String WHITELIST_REASON = "quality exemption whitelist";
    public static final String BUILT_IN_AGENT_REASON = "built-in system agent";
    public static final String PERSONAL_ASSISTANT_REASON = "personal assistant copy";
    /** Third-party skills arrive from a repository the platform does not author, so our authoring rules do not apply. */
    public static final String THIRD_PARTY_REPO_REASON = "third-party repo import";

    public static List<String> normalize(List<String> entries) {
        if (entries == null) return List.of();
        var normalized = new LinkedHashSet<String>();
        for (var entry : entries) {
            if (entry == null) continue;
            var trimmed = entry.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.length() > MAX_ENTRY_LENGTH) {
                throw new BadRequestException("quality exemption entry exceeds " + MAX_ENTRY_LENGTH
                    + " characters: " + trimmed);
            }
            if (trimmed.indexOf('*') >= 0) {
                throw new BadRequestException("quality exemptions do not support wildcards: " + trimmed);
            }
            normalized.add(trimmed);
        }
        if (normalized.size() > MAX_ENTRIES) {
            throw new BadRequestException("quality exemptions exceed " + MAX_ENTRIES + " entries");
        }
        return List.copyOf(normalized);
    }

    /** Returns the whitelist entry that matches one of the subjects, or null when none matches. */
    public static String match(List<String> exemptions, String... subjects) {
        if (exemptions == null || exemptions.isEmpty()) return null;
        for (var entry : exemptions) {
            if (entry == null) continue;
            for (var subject : subjects) {
                if (subject != null && subject.equals(entry)) return entry;
            }
        }
        return null;
    }

    /** Built-in agents never need a whitelist entry: system agents and every personal assistant copy. */
    public static String builtInAgentReason(AgentDefinition entity) {
        if (entity == null) return null;
        if (Boolean.TRUE.equals(entity.systemDefault)) return BUILT_IN_AGENT_REASON;
        if (entity.forkedFrom != null) return PERSONAL_ASSISTANT_REASON;
        return null;
    }

    public static List<String> copyOf(List<String> entries) {
        return entries == null ? List.of() : new ArrayList<>(entries);
    }

    private QualityExemptions() {
    }
}
