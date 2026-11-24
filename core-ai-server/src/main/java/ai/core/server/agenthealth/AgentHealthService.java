package ai.core.server.agenthealth;

import ai.core.api.server.agent.AgentHealthView;
import ai.core.api.server.agent.AgentQualityStatusView;
import ai.core.api.server.agent.AgentReferenceIssueView;
import ai.core.server.apiuser.PermissionService;
import ai.core.server.apitoolhub.ApiToolCatalogService;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolSourceType;
import ai.core.server.quality.AgentQualityStore;
import ai.core.server.quality.QualityPolicyService;
import ai.core.server.quality.domain.AgentQualityRun;
import ai.core.server.quality.domain.QualityGateMode;
import ai.core.server.quality.domain.QualityGateResult;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.skill.SkillStore;
import ai.core.server.user.UserStore;
import ai.core.server.util.IdLists;
import core.framework.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Derives agent reference health and the latest author-side quality result. The derived view never
 * reads the issue ledger's state; it only reads issue names as a display fallback.
 *
 * @author core-ai
 */
public class AgentHealthService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentHealthService.class);
    private static final Set<QualityGateMode> AUTHOR_MODES = Set.of(
        QualityGateMode.PRECHECK, QualityGateMode.PUBLISH, QualityGateMode.FORK);

    @Inject
    SkillStore skillStore;
    @Inject
    ApiToolCatalogService apiToolCatalogService;
    @Inject
    AgentQualityStore qualityStore;
    @Inject
    QualityPolicyService qualityPolicyService;
    @Inject
    PermissionService permissionService;
    @Inject
    UserStore userStore;
    @Inject
    AgentReferenceIssueStore issueStore;

    // ── Derived views ────────────────────────────────────────────────────────────
    public AgentHealthView snapshot(AgentDefinition entity, String viewerUserId) {
        if (entity == null) return null;
        return snapshot(List.of(entity), viewerUserId).get(entity.id);
    }

    public Map<String, AgentHealthView> snapshot(List<AgentDefinition> entities, String viewerUserId) {
        if (entities == null || entities.isEmpty()) return Map.of();
        boolean viewerCanManage = canManage(viewerUserId);
        Context context;
        try {
            context = context(entities, viewerUserId, viewerCanManage);
        } catch (RuntimeException e) {
            LOGGER.warn("agent health snapshot failed: {}", e.getMessage());
            return allUnknown(entities);
        }
        var result = new HashMap<String, AgentHealthView>();
        for (var entity : entities) {
            try {
                result.put(entity.id, health(entity, viewerUserId, viewerCanManage, context));
            } catch (RuntimeException e) {
                LOGGER.warn("failed to compute health, agentId={}: {}", entity.id, e.getMessage());
                result.put(entity.id, unknownHealth());
            }
        }
        return result;
    }

    private Map<String, AgentHealthView> allUnknown(List<AgentDefinition> entities) {
        var result = new HashMap<String, AgentHealthView>();
        for (var entity : entities) result.put(entity.id, unknownHealth());
        return result;
    }

    private Context context(List<AgentDefinition> entities, String viewerUserId, boolean viewerCanManage) {
        var context = new Context();
        var skillIds = new LinkedHashSet<String>();
        var agentIds = new ArrayList<String>(entities.size());
        for (var entity : entities) {
            agentIds.add(entity.id);
            skillIds.addAll(skillIds(entity));
        }
        context.skills = probeSkills(skillIds);
        context.catalog = apiToolCatalogService == null ? null : apiToolCatalogService.successSnapshot();
        boolean canSeeQuality = viewerCanManage
            || viewerUserId != null && entities.stream().anyMatch(entity -> viewerUserId.equals(entity.userId));
        if (canSeeQuality) {
            try {
                context.qualityRuns = qualityStore.findLatestCompletedRuns(agentIds, AUTHOR_MODES);
            } catch (RuntimeException e) {
                context.qualityFailed = true;
                LOGGER.warn("failed to read latest quality runs: {}", e.getMessage());
            }
            try {
                context.policyRevision = qualityPolicyService.activeRevision();
            } catch (RuntimeException e) {
                LOGGER.warn("failed to read active quality policy revision: {}", e.getMessage());
            }
        }
        if (issueStore != null && context.skills.verified
            && skillIds.stream().anyMatch(id -> !context.skills.names.containsKey(id))) {
            try {
                context.issueNames = issueStore.listActiveByAgentIds(agentIds).stream()
                    .filter(issue -> issue.refName != null)
                    .collect(Collectors.toMap(
                        issue -> issueKey(issue.agentId, issue.refType, issue.refId),
                        issue -> issue.refName, (left, right) -> left));
            } catch (RuntimeException e) {
                LOGGER.debug("failed to read issue display names: {}", e.getMessage());
            }
        }
        return context;
    }

    private AgentHealthView health(AgentDefinition entity, String viewerUserId, boolean viewerCanManage, Context context) {
        var owner = viewerUserId != null && viewerUserId.equals(entity.userId);
        var editorAudience = viewerCanManage || owner;
        var views = new ArrayList<AgentReferenceIssueView>();
        boolean broken = false;
        boolean unverified = false;

        for (var entry : collectRefs(entity).entrySet()) {
            var key = entry.getKey();
            var check = check(key, context.skills, context.catalog);
            if (check.state == RefState.OK) continue;
            var sourceNames = sourceNames(entry.getValue(), editorAudience);
            if (sourceNames.isEmpty()) continue;
            var view = referenceIssueView(entity, key, check, sourceNames, context);
            views.add(view);
            if (check.state == RefState.BROKEN) broken = true;
            else unverified = true;
        }

        var quality = qualityView(entity, viewerUserId, viewerCanManage, context);
        if (quality != null) {
            if ("FAILED".equals(quality.state) || "UNAVAILABLE".equals(quality.state)) broken = true;
            else if ("UNKNOWN".equals(quality.state)) unverified = true;
        }

        var view = new AgentHealthView();
        view.status = broken ? "ATTENTION" : unverified ? "UNKNOWN" : "OK";
        view.needsAttention = broken;
        view.referenceIssues = views;
        view.quality = quality;
        return view;
    }

    private AgentReferenceIssueView referenceIssueView(AgentDefinition entity, RefKey key, Check check,
                                                       List<String> sourceNames, Context context) {
        var view = new AgentReferenceIssueView();
        view.refType = key.type;
        view.refId = key.id;
        view.refName = displayName(entity, key, context);
        view.state = check.state == RefState.BROKEN ? "BROKEN" : "UNVERIFIED";
        view.reason = check.reason;
        view.sources = sourceNames;
        view.inDraft = sourceNames.contains("DRAFT");
        view.inPublished = sourceNames.contains("PUBLISHED");
        view.message = message(view.refName, check.reason);
        return view;
    }

    private AgentQualityStatusView qualityView(AgentDefinition entity, String viewerUserId, boolean viewerCanManage,
                                               Context context) {
        boolean owner = viewerUserId != null && viewerUserId.equals(entity.userId);
        if (!viewerCanManage && !owner) return null;

        var view = new AgentQualityStatusView();
        int currentRevision = revision(entity);
        view.currentRevision = currentRevision;
        if (context.qualityFailed) {
            view.state = "UNKNOWN";
            view.message = "Quality status could not be read.";
            return view;
        }
        var run = context.qualityRuns.get(entity.id);
        if (run == null) {
            view.state = "NONE";
            return view;
        }

        view.runId = run.id;
        view.mode = run.mode == null ? null : run.mode.name();
        view.gateResult = run.gateResult == null ? null : run.gateResult.name();
        view.blockFailCount = run.blockFailCount;
        view.incompleteCount = run.incompleteCount;
        view.warnCount = run.warnCount;
        view.exempted = run.exempted;
        view.checkedAt = run.finishedAt != null ? run.finishedAt : run.createdAt;
        view.definitionRevision = run.definitionRevision;
        view.policyRevision = run.policyRevision;
        view.policyChanged = context.policyRevision != null && run.policyRevision != null
            && !context.policyRevision.equals(run.policyRevision);
        view.contentChanged = contentChanged(entity, run, currentRevision);

        if (Boolean.TRUE.equals(run.exempted)) {
            view.state = "PASSED";
            view.message = "Quality check is exempted.";
        } else if (run.gateResult == null) {
            view.state = "UNKNOWN";
            view.message = "Quality run result is unavailable.";
        } else if (run.gateResult == QualityGateResult.PASSED) {
            view.state = "PASSED";
            view.message = "Last quality check passed.";
        } else if (run.gateResult == QualityGateResult.REJECTED) {
            view.state = "FAILED";
            view.message = "Last quality check failed"
                + (run.blockFailCount == null ? "." : ": " + run.blockFailCount + " blocking issue"
                + (run.blockFailCount == 1 ? "." : "s."));
        } else {
            view.state = "UNAVAILABLE";
            view.message = "Last quality check could not complete.";
        }
        return view;
    }

    private boolean contentChanged(AgentDefinition entity, AgentQualityRun run, int currentRevision) {
        if (run.definitionRevision == null) return false;
        boolean sameRevision = run.definitionRevision == currentRevision;
        boolean publishedByRun = entity.qualityReceipt != null
            && run.id != null && run.id.equals(entity.qualityReceipt.runId);
        boolean publishedNextRevision = run.definitionRevision + 1 == currentRevision && publishedByRun;
        return !(sameRevision || publishedNextRevision);
    }

    private AgentHealthView unknownHealth() {
        var view = new AgentHealthView();
        view.status = "UNKNOWN";
        view.needsAttention = Boolean.FALSE;
        view.referenceIssues = List.of();
        return view;
    }

    // ── Reference collection and checks ──────────────────────────────────────────

    Map<RefKey, Set<Source>> collectRefs(AgentDefinition entity) {
        var refs = new LinkedHashMap<RefKey, Set<Source>>();
        addSkillRefs(refs, entity.skillIds, Source.DRAFT);
        addApiRefs(refs, entity.tools, Source.DRAFT);
        AgentPublishedConfig published = entity.publishedConfig;
        if (published != null) {
            addSkillRefs(refs, published.skillIds, Source.PUBLISHED);
            addApiRefs(refs, published.tools, Source.PUBLISHED);
        }
        return refs;
    }

    private void addSkillRefs(Map<RefKey, Set<Source>> refs, List<String> skillIds, Source source) {
        for (var id : IdLists.clean(skillIds)) {
            refs.computeIfAbsent(new RefKey("SKILL", id), key -> EnumSet.noneOf(Source.class)).add(source);
        }
    }

    private void addApiRefs(Map<RefKey, Set<Source>> refs, List<ToolRef> tools, Source source) {
        if (tools == null) return;
        for (var ref : tools) {
            if (!isApiRef(ref)) continue;
            refs.computeIfAbsent(new RefKey("API", ref.id), key -> EnumSet.noneOf(Source.class)).add(source);
        }
    }

    private boolean isApiRef(ToolRef ref) {
        if (ref == null || ref.id == null) return false;
        if (ref.id.startsWith("api-app:") || ref.id.startsWith("api-service:") || ref.id.startsWith("api-operation:")) {
            return true;
        }
        return ref.type == ToolSourceType.API && !"builtin-service-api".equals(ref.id);
    }

    List<String> skillIds(AgentDefinition entity) {
        var ids = new ArrayList<>(IdLists.clean(entity.skillIds));
        if (entity.publishedConfig != null) ids.addAll(IdLists.clean(entity.publishedConfig.skillIds));
        return ids;
    }

    Check check(RefKey key, SkillProbe skills, ApiToolCatalogService.CatalogSnapshot catalog) {
        if ("SKILL".equals(key.type)) {
            if (!skills.verified) return Check.unverified("SKILL_PROBE_UNAVAILABLE");
            return skills.names.containsKey(key.id)
                ? Check.ok() : Check.broken("SKILL_NOT_FOUND");
        }
        if (catalog == null) return Check.unverified("API_TOOL_UNVERIFIED");
        if ("builtin-service-api".equals(key.id)) return Check.ok();
        if (key.id.startsWith("api-operation:")) {
            var parts = key.id.substring("api-operation:".length()).split(":", 3);
            if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
                return Check.broken("MALFORMED_REFERENCE");
            }
            return catalog.byRefId().containsKey(key.id)
                ? Check.ok() : Check.broken("API_TOOL_NOT_FOUND");
        }
        if (key.id.startsWith("api-service:")) {
            var parts = key.id.substring("api-service:".length()).split(":", 2);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                return Check.broken("MALFORMED_REFERENCE");
            }
            return catalog.operationCountByService().getOrDefault(parts[0] + ":" + parts[1], 0) > 0
                ? Check.ok() : Check.broken("API_TOOL_NOT_FOUND");
        }
        if (key.id.startsWith("api-app:")) {
            var app = key.id.substring("api-app:".length());
            if (app.isBlank()) return Check.broken("MALFORMED_REFERENCE");
            return catalog.operationCountByApp().getOrDefault(app, 0) > 0
                ? Check.ok() : Check.broken("API_TOOL_NOT_FOUND");
        }
        return Check.broken("MALFORMED_REFERENCE");
    }

    SkillProbe probeSkills(Collection<String> skillIds) {
        var ids = IdLists.clean(new ArrayList<>(skillIds));
        if (ids.isEmpty()) return new SkillProbe(true, Map.of());
        try {
            return new SkillProbe(true, skillStore.findNames(ids));
        } catch (RuntimeException e) {
            LOGGER.warn("failed to probe skills: {}", e.getMessage());
            return new SkillProbe(false, Map.of());
        }
    }

    private String displayName(AgentDefinition entity, RefKey key, Context context) {
        if ("SKILL".equals(key.type)) {
            var name = context.skills.names.get(key.id);
            if (name != null) return name;
            name = context.issueNames.get(issueKey(entity.id, key.type, key.id));
            return name == null ? key.id : name;
        }
        return apiDisplayName(key.id);
    }

    private String apiDisplayName(String refId) {
        if (refId.startsWith("api-operation:")) {
            var parts = refId.substring("api-operation:".length()).split(":", 3);
            if (parts.length == 3) return parts[0] + " > " + parts[1] + " > " + parts[2];
        }
        if (refId.startsWith("api-service:")) {
            var parts = refId.substring("api-service:".length()).split(":", 2);
            if (parts.length == 2) return parts[0] + " > " + parts[1];
        }
        if (refId.startsWith("api-app:")) return refId.substring("api-app:".length());
        return refId;
    }

    private String message(String name, String reason) {
        return switch (reason == null ? "" : reason) {
            case "SKILL_NOT_FOUND" -> "Skill \"" + name + "\" no longer exists.";
            case "SKILL_PROBE_UNAVAILABLE" -> "Skill reference could not be verified.";
            case "API_TOOL_NOT_FOUND" -> "API tool \"" + name + "\" is no longer available.";
            case "API_TOOL_UNVERIFIED" -> "API tool reference could not be verified because the catalog is unavailable.";
            case "MALFORMED_REFERENCE" -> "Tool reference \"" + name + "\" is malformed.";
            default -> "Reference \"" + name + "\" is unavailable.";
        };
    }

    List<String> sourceNames(Set<Source> sources, boolean editorAudience) {
        var names = new ArrayList<String>(2);
        if (editorAudience && sources.contains(Source.DRAFT)) names.add("DRAFT");
        if (sources.contains(Source.PUBLISHED)) names.add("PUBLISHED");
        return names;
    }

    boolean probeVerified(RefKey key, SkillProbe skills, ApiToolCatalogService.CatalogSnapshot catalog) {
        return "SKILL".equals(key.type) ? skills.verified : catalog != null;
    }

    String issueName(RefKey key, SkillProbe skills, Map<String, String> overrides, String existing) {
        if ("SKILL".equals(key.type)) {
            var name = overrides.get(key.id);
            if (name != null) return name;
            name = skills.names.get(key.id);
            if (name != null) return name;
            return existing == null ? key.id : existing;
        }
        return apiDisplayName(key.id);
    }

    private int revision(AgentDefinition entity) {
        return entity.definitionRevision == null ? 0 : entity.definitionRevision;
    }

    String issueKey(String agentId, String refType, String refId) {
        return agentId + "|" + refType + "|" + refId;
    }

    private boolean canManage(String userId) {
        if (userId == null) return false;
        try {
            if (userStore.get(userId).map(user -> "admin".equals(user.role)).orElse(Boolean.FALSE)) return true;
        } catch (RuntimeException e) {
            LOGGER.debug("failed to read user role, userId={}: {}", userId, e.getMessage());
        }
        try {
            return permissionService.has(userId, PermissionCodes.AGENT_MANAGE);
        } catch (RuntimeException e) {
            return false;
        }
    }

    record SkillProbe(boolean verified, Map<String, String> names) { }

    record RefKey(String type, String id) { }

    record Check(RefState state, String reason) {
        static Check ok() {
            return new Check(RefState.OK, null);
        }

        static Check broken(String reason) {
            return new Check(RefState.BROKEN, reason);
        }

        static Check unverified(String reason) {
            return new Check(RefState.UNVERIFIED, reason);
        }
    }

    enum RefState { OK, BROKEN, UNVERIFIED }

    enum Source { DRAFT, PUBLISHED }

    private static final class Context {
        SkillProbe skills;
        ApiToolCatalogService.CatalogSnapshot catalog;
        Map<String, AgentQualityRun> qualityRuns = Map.of();
        boolean qualityFailed;
        Integer policyRevision;
        Map<String, String> issueNames = Map.of();
    }
}

