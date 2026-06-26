package ai.core.server.workflow;

import ai.core.server.domain.ArtifactRef;
import ai.core.server.sandbox.SandboxService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Derives the set of upstream artifact files the platform must stage into a consumer node's sandbox, from the
 * {@code nodes.<id>.artifacts} references the node's input actually makes (design §5.3.2: staging set = explicit
 * reference closure — files travel by reference in the pool, the platform materializes the bytes, the LLM only
 * decides). Granularity rule: a whole-array / whole-object / {@code .path} reference stages the file(s); a
 * metadata-only reference ({@code .url}, {@code .file_name}, …) does not — its intent is link forwarding.
 *
 * <p>The staged path {@code /tmp/inputs/<srcNodeId>/<fileName>} is a pure function of the reference (no sandbox
 * needed), so prompts can render it before the sandbox materializes; {@link VariablePool#stagedView()} injects it
 * as the transient {@code path} field. File names are sanitized to a single path segment so an upstream-supplied
 * name can never escape the staging directory.
 *
 * @author Xander
 */
public final class ArtifactStaging {
    public static final String STAGING_ROOT = "/tmp/inputs";

    // {{ nodes.<id>.artifacts(.token)* }} — group 1 = node id, group 2 = dotted suffix after "artifacts"
    private static final Pattern TEMPLATE_REFERENCE = Pattern.compile("\\{\\{\\s*nodes\\.([A-Za-z_][A-Za-z0-9_]*)\\.artifacts((?:\\.[A-Za-z0-9_]+)*)\\s*}}");
    // bare selector form (CODE input map values): nodes.<id>.artifacts(.token)*
    private static final Pattern SELECTOR_REFERENCE = Pattern.compile("nodes\\.([A-Za-z_][A-Za-z0-9_]*)\\.artifacts((?:\\.[A-Za-z0-9_]+)*)");

    private ArtifactStaging() {
    }

    /** The deterministic in-sandbox path an upstream node's artifact is staged at. */
    public static String pathOf(String srcNodeId, String fileName) {
        return STAGING_ROOT + "/" + srcNodeId + "/" + sanitizeFileName(fileName);
    }

    /** Staging set for a {@code {{ … }}} input template (AGENT nodes). */
    public static List<SandboxService.StagedFile> scanTemplate(String template, VariablePool pool) {
        return scan(template, TEMPLATE_REFERENCE, pool);
    }

    /** Staging set for a bare selector (CODE node input map values). */
    public static List<SandboxService.StagedFile> scanSelector(String selector, VariablePool pool) {
        return scan(selector, SELECTOR_REFERENCE, pool);
    }

    private static List<SandboxService.StagedFile> scan(String text, Pattern pattern, VariablePool pool) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        var staged = new LinkedHashMap<String, SandboxService.StagedFile>();   // keyed by target path -> dedup
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            collect(matcher.group(1), matcher.group(2), pool, staged);
        }
        return List.copyOf(staged.values());
    }

    /**
     * The artifact references a {@code {{ … }}} template delivers — its {@code nodes.<id>.artifacts} mentions
     * resolved to {@link ArtifactRef}s with the same file-consuming granularity as staging (whole-array /
     * whole-object / {@code .path} -> the file(s); a metadata-only reference such as {@code .url} -> none, its
     * intent is link forwarding). Lets an END node lift the files its {@code output} template actually delivers
     * into the run deliverables instead of them surfacing only as text. Duplicates by file_id are left for the
     * caller's {@link ArtifactRef#union} to fold.
     */
    public static List<ArtifactRef> referencedFiles(String template, VariablePool pool) {
        var refs = new ArrayList<ArtifactRef>();
        for (ReferencedFiles group : referencedFileGroups(template, pool)) {
            refs.addAll(group.artifacts());
        }
        return refs;
    }

    /** Same as {@link #referencedFiles}, but keeps the source node id for flow-order delivery sorting. */
    public static List<ReferencedFiles> referencedFileGroups(String template, VariablePool pool) {
        if (template == null || template.isBlank()) {
            return List.of();
        }
        var groups = new ArrayList<ReferencedFiles>();
        Matcher matcher = TEMPLATE_REFERENCE.matcher(template);
        while (matcher.find()) {
            List<ArtifactRef> refs = referencedRefs(matcher.group(1), matcher.group(2), pool);
            if (!refs.isEmpty()) {
                groups.add(new ReferencedFiles(matcher.group(1), refs));
            }
        }
        return groups;
    }

    public record ReferencedFiles(String nodeId, List<ArtifactRef> artifacts) {
        public ReferencedFiles {
            artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        }
    }

    private static void collect(String nodeId, String suffix, VariablePool pool, Map<String, SandboxService.StagedFile> out) {
        for (ArtifactRef ref : referencedRefs(nodeId, suffix, pool)) {
            add(nodeId, ref, out);
        }
    }

    // The artifact refs a "nodes.<id>.artifacts<suffix>" reference points at, with file-consuming granularity:
    // whole-array / whole-object ({{ ….artifacts.0 }}) / local-path ({{ ….artifacts.0.path }}) -> the file(s);
    // a metadata-only reference ({{ ….artifacts.0.url }}, .file_name, …) -> none (its intent is link forwarding).
    private static List<ArtifactRef> referencedRefs(String nodeId, String suffix, VariablePool pool) {
        List<ArtifactRef> refs = pool.artifactsOf(nodeId);
        if (refs.isEmpty()) {
            return List.of();
        }
        String[] tokens = suffix.isEmpty() ? new String[0] : suffix.substring(1).split("\\.");
        if (tokens.length == 0) {
            return refs;
        }
        int index = parseIndex(tokens[0]);
        if (index < 0 || index >= refs.size()) {
            return List.of();   // not an indexed reference (or out of range)
        }
        return tokens.length == 1 || "path".equals(tokens[1]) ? List.of(refs.get(index)) : List.of();
    }

    private static void add(String nodeId, ArtifactRef ref, Map<String, SandboxService.StagedFile> out) {
        if (ref.fileId == null || ref.fileName == null || ref.fileName.isBlank()) {
            return;
        }
        String path = pathOf(nodeId, ref.fileName);
        out.putIfAbsent(path, new SandboxService.StagedFile(ref.fileId, ref.fileName, path));
    }

    // Keep only the last path segment and strip traversal — a staged file always lands inside its node directory.
    private static String sanitizeFileName(String fileName) {
        String name = fileName.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replace("..", "_");
        return name.isBlank() ? "file" : name;
    }

    private static int parseIndex(String token) {
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
