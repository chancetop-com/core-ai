package ai.core.server.comparison;

import ai.core.api.server.tool.ToolRefView;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolSourceType;
import core.framework.web.exception.BadRequestException;

import java.util.ArrayList;
import java.util.List;

/**
 * Effective-tool whitelist handling for comparison variants: normalized refs, safe defaults (builtin
 * tools only — MCP / API / agent / LLM-call refs may write to external systems and are opt-in) and
 * subset validation against the variant config.
 *
 * @author stephen
 */
public final class ComparisonToolFreeze {
    public static List<ToolRef> defaultEffective(List<ToolRef> configured) {
        var normalized = normalizeAll(configured);
        var result = new ArrayList<ToolRef>();
        for (var ref : normalized) {
            if (ref.type == ToolSourceType.BUILTIN && !contains(result, ref)) result.add(ref);
        }
        return result;
    }

    /**
     * Priority: explicit confirmation (validated against the config) → what the draft stored → safe default.
     */
    public static List<ToolRef> resolve(List<ToolRefView> requested, List<ToolRef> stored, List<ToolRef> configured) {
        var normalizedConfigured = normalizeAll(configured);
        if (requested != null) {
            var refs = fromViews(requested);
            for (var ref : refs) {
                if (!contains(normalizedConfigured, ref)) {
                    throw new BadRequestException("tool is not part of the variant config: " + ref.id);
                }
            }
            return refs;
        }
        return stored != null ? normalizeAll(stored) : defaultEffective(normalizedConfigured);
    }

    public static List<ToolRef> fromViews(List<ToolRefView> views) {
        if (views == null || views.isEmpty()) return List.of();
        var result = new ArrayList<ToolRef>(views.size());
        for (var view : views) {
            if (view == null || view.id == null || view.id.isBlank()) continue;
            var ref = new ToolRef();
            ref.id = view.id;
            ref.type = view.type != null ? ToolSourceType.valueOf(view.type) : null;
            ref.source = view.source;
            if (ref.type == null) ref.inferTypeFromId();
            if (!contains(result, ref)) result.add(ref);
        }
        return result;
    }

    public static List<ToolRef> normalizeAll(List<ToolRef> refs) {
        if (refs == null || refs.isEmpty()) return List.of();
        var result = new ArrayList<ToolRef>(refs.size());
        for (var ref : refs) {
            var normalized = normalize(ref);
            if (normalized != null && !contains(result, normalized)) result.add(normalized);
        }
        return result;
    }

    /** Guards the start of a comparison: tools confirmed against an earlier config must not silently survive a source change. */
    public static void requireSubset(List<ToolRef> tools, List<ToolRef> configured) {
        if (tools == null) return;
        var normalizedConfigured = normalizeAll(configured);
        for (var ref : normalizeAll(tools)) {
            if (!contains(normalizedConfigured, ref)) {
                throw new BadRequestException("confirmed tools no longer match the variant config; confirm the tool set again");
            }
        }
    }

    public static ToolRefView toView(ToolRef ref) {
        var view = new ToolRefView();
        view.id = ref.id;
        view.type = ref.type != null ? ref.type.name() : null;
        view.source = ref.source;
        return view;
    }

    private static ToolRef normalize(ToolRef ref) {
        if (ref == null || ref.id == null || ref.id.isBlank()) return null;
        var copy = new ToolRef();
        copy.id = ref.id;
        copy.type = ref.type;
        copy.source = ref.source;
        if (copy.type == null) copy.inferTypeFromId();
        return copy;
    }

    private static boolean contains(List<ToolRef> refs, ToolRef ref) {
        for (var candidate : refs) {
            if (candidate.id.equals(ref.id)
                && candidate.type == ref.type
                && java.util.Objects.equals(candidate.source, ref.source)) {
                return true;
            }
        }
        return false;
    }

    private ComparisonToolFreeze() {
    }
}
