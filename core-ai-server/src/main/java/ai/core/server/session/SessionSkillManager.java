package ai.core.server.session;

import ai.core.server.domain.AgentDefinition;
import ai.core.server.skill.MongoSkillProvider;
import ai.core.server.skill.ServerSkillTool;
import ai.core.server.skill.SkillArchiveBuilder;
import ai.core.server.skill.SkillSdkCapabilityResolver;
import ai.core.server.skill.SkillService;
import ai.core.server.skill.telemetry.SkillPapercutCategory;
import ai.core.server.skill.telemetry.SkillTelemetryBuffer;
import ai.core.server.skill.telemetry.SkillTelemetryService;
import ai.core.server.skillhub.SkillCatalogService;
import ai.core.server.skillhub.SkillHubAccessPolicy;
import ai.core.server.util.IdLists;
import ai.core.session.InProcessAgentSession;
import ai.core.skill.SkillMetadata;
import ai.core.skill.SkillRegistry;
import ai.core.skill.SkillSdkCapabilityManifest;
import ai.core.skill.telemetry.SkillTelemetryLifecycle;
import ai.core.skill.telemetry.SkillTelemetrySink;
import ai.core.tool.ToolCall;
import ai.core.tool.tools.ReadSkillResourceTool;
import ai.core.tool.tools.ReportSkillPapercutTool;
import ai.core.tool.tools.SearchSkillsTool;
import core.framework.web.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * @author stephen
 */
public class SessionSkillManager {
    private final Logger logger = LoggerFactory.getLogger(SessionSkillManager.class);
    private final ConcurrentMap<String, SessionSkillState> sessionSkillStates = new ConcurrentHashMap<>();

    private final SkillService skillService;
    private final MongoSkillProvider mongoSkillProvider;
    private final SkillArchiveBuilder skillArchiveBuilder;
    private final ChatMessageService chatMessageService;
    private SkillSdkCapabilityResolver capabilityResolver;
    private SkillTelemetryBuffer telemetryBuffer;
    private SkillTelemetryService telemetryService;
    private SkillCatalogService catalogService;
    private SkillHubAccessPolicy accessPolicy;

    public SessionSkillManager(SkillService skillService, MongoSkillProvider mongoSkillProvider,
                                SkillArchiveBuilder skillArchiveBuilder, ChatMessageService chatMessageService) {
        this.skillService = skillService;
        this.mongoSkillProvider = mongoSkillProvider;
        this.skillArchiveBuilder = skillArchiveBuilder;
        this.chatMessageService = chatMessageService;
    }

    public void setCapabilityResolver(SkillSdkCapabilityResolver capabilityResolver) {
        this.capabilityResolver = capabilityResolver;
    }

    public void setTelemetryBuffer(SkillTelemetryBuffer telemetryBuffer) {
        this.telemetryBuffer = telemetryBuffer;
    }

    public void setTelemetryService(SkillTelemetryService telemetryService) {
        this.telemetryService = telemetryService;
    }

    public void setCatalogService(SkillCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    public void setAccessPolicy(SkillHubAccessPolicy accessPolicy) {
        this.accessPolicy = accessPolicy;
    }

    public SearchSkillsTool buildSearchSkillsTool(SkillRegistry registry, String callerNamespace,
                                                  boolean allowCatalogSkillSearch) {
        SearchSkillsTool.SearchBackend backend;
        if (allowCatalogSkillSearch && catalogService != null) {
            backend = (query, namespaceFilter, limit) -> searchControlledCatalog(
                    registry, callerNamespace, query, namespaceFilter, limit);
        } else {
            backend = SearchSkillsTool.attachedOnlyBackend(registry);
        }
        return SearchSkillsTool.builder().backend(backend).build();
    }

    public List<String> unloadSkills(String sessionId, List<String> skillIds, String callerUserId) {
        var cleanSkillIds = IdLists.clean(skillIds);
        if (!cleanSkillIds.isEmpty()) {
            skillService.resolveAccessibleSkills(cleanSkillIds, callerUserId);
        }
        var state = sessionSkillStates.get(sessionId);
        if (state == null || cleanSkillIds.isEmpty()) {
            return state == null ? List.of() : List.copyOf(state.allowedIds);
        }
        state.allowedIds.removeAll(cleanSkillIds);
        cleanSkillIds.forEach(state.skills::remove);
        state.registry.invalidateCache();
        chatMessageService.removeLoadedSkillIds(sessionId, cleanSkillIds);
        recomputeCapabilities(state, null);
        return List.copyOf(state.allowedIds);
    }

    public List<String> loadSkills(InProcessAgentSession session, List<String> skillIds, String callerUserId) {
        var cleanSkillIds = IdLists.clean(skillIds);
        if (cleanSkillIds.isEmpty()) return List.of();
        var qualifiedNames = applyCallerSkillsToSession(session, cleanSkillIds, callerUserId);
        chatMessageService.addLoadedSkillIds(session.id(), cleanSkillIds);
        return qualifiedNames;
    }

    ResolvedDefinitionSkills resolveAccessibleDefinitionSkills(AgentDefinition definition, String callerUserId) {
        var cleanSkillIds = definitionSkillIds(definition);
        if (cleanSkillIds.isEmpty()) return new ResolvedDefinitionSkills(List.of(), List.of());
        var skills = skillService.resolveAccessibleSkills(cleanSkillIds, callerUserId);
        return new ResolvedDefinitionSkills(cleanSkillIds, skills);
    }

    ResolvedDefinitionSkills resolveDefinitionSkills(AgentDefinition definition, String callerUserId,
                                                      boolean ownedEditable) {
        return ownedEditable ? resolveAccessibleDefinitionSkills(definition, callerUserId) : null;
    }

    List<String> loadResolvedDefinitionSkills(InProcessAgentSession session, ResolvedDefinitionSkills resolved) {
        if (resolved.skillIds.isEmpty()) return List.of();
        var qualifiedNames = applyResolvedSkillsToSession(session, resolved.skillIds, resolved.skills, null);
        chatMessageService.addLoadedSkillIds(session.id(), resolved.skillIds);
        return qualifiedNames;
    }

    public List<String> loadFrozenSkillsFromDefinition(InProcessAgentSession session, AgentDefinition definition) {
        var cleanSkillIds = definitionSkillIds(definition);
        if (cleanSkillIds.isEmpty()) return List.of();
        try {
            var qualifiedNames = applySkillsToSession(session, cleanSkillIds);
            chatMessageService.addLoadedSkillIds(session.id(), cleanSkillIds);
            return qualifiedNames;
        } catch (Exception e) {
            logger.warn("failed to load skills from definition, sessionId={}, skillIds={}", session.id(), cleanSkillIds, e);
            return List.of();
        }
    }

    List<String> loadDefinitionSkills(InProcessAgentSession session, AgentDefinition definition,
                                      ResolvedDefinitionSkills resolved) {
        return resolved != null
                ? loadResolvedDefinitionSkills(session, resolved)
                : loadFrozenSkillsFromDefinition(session, definition);
    }

    private List<String> definitionSkillIds(AgentDefinition definition) {
        var skillIds = definition.publishedConfig != null
                ? definition.publishedConfig.skillIds : definition.skillIds;
        return IdLists.clean(skillIds);
    }

    List<String> applySkillsToSession(InProcessAgentSession session, List<String> skillIds) {
        var cleanSkillIds = IdLists.clean(skillIds);
        if (cleanSkillIds.isEmpty()) return List.of();
        var skills = skillService.resolveSkills(cleanSkillIds);
        if (skills.isEmpty()) {
            throw new NotFoundException("no skills found for ids: " + cleanSkillIds);
        }
        return applyResolvedSkillsToSession(session, cleanSkillIds, skills, null);
    }

    List<String> applyCallerSkillsToSession(InProcessAgentSession session, List<String> skillIds,
                                            String callerUserId) {
        var cleanSkillIds = IdLists.clean(skillIds);
        if (cleanSkillIds.isEmpty()) return List.of();
        var skills = skillService.resolveAccessibleSkills(cleanSkillIds, callerUserId);
        return applyResolvedSkillsToSession(session, cleanSkillIds, skills, callerUserId);
    }

    void restoreDefinitionSkills(InProcessAgentSession session, List<String> skillIds) {
        var cleanSkillIds = IdLists.clean(skillIds);
        if (cleanSkillIds.isEmpty()) return;
        try {
            applySkillsToSession(session, cleanSkillIds);
            logger.info("restored {} definition skill(s) for session {}", cleanSkillIds.size(), session.id());
        } catch (Exception e) {
            logger.warn("failed to restore definition skills, sessionId={}", session.id(), e);
        }
    }

    private List<String> applyResolvedSkillsToSession(InProcessAgentSession session, List<String> cleanSkillIds,
                                                       List<SkillMetadata> skills, String callerUserId) {
        var state = sessionSkillStates.computeIfAbsent(session.id(), k -> initSkillState(session));
        state.allowedIds.addAll(cleanSkillIds);
        for (int i = 0; i < cleanSkillIds.size() && i < skills.size(); i++) {
            state.skills.put(cleanSkillIds.get(i), skills.get(i));
        }
        state.registry.invalidateCache();
        recomputeCapabilities(state, callerUserId);
        return skills.stream().map(SkillMetadata::getQualifiedName).toList();
    }

    private void recomputeCapabilities(SessionSkillState state, String callerUserId) {
        if (state == null || capabilityResolver == null) return;
        var agent = state.session.agent();
        if (agent == null) return;
        var userId = callerUserId != null ? callerUserId : agent.getExecutionContext().getUserId();
        try {
            agent.getExecutionContext().setSkillCapabilities(
                    capabilityResolver.resolve(state.session.id(), userId, state.skills));
        } catch (RuntimeException e) {
            logger.warn("failed to resolve skill capabilities, sessionId={}", state.session.id(), e);
            agent.getExecutionContext().setSkillCapabilities(List.of());
        }
    }

    public void removeSkillState(String sessionId) {
        sessionSkillStates.remove(sessionId);
    }

    private SessionSkillState initSkillState(InProcessAgentSession session) {
        var fresh = new SessionSkillState(session);
        fresh.registry.addProvider(mongoSkillProvider.scoped(fresh.allowedIds));
        var sink = telemetryBuffer != null ? telemetryBuffer.asSink() : SkillTelemetrySink.NOOP;
        ToolCall skillTool = ServerSkillTool.builder()
                .registry(fresh.registry)
                .skillService(skillService)
                .archiveBuilder(skillArchiveBuilder)
                .telemetryBuffer(telemetryBuffer)
                .build();
        ToolCall readResourceTool = ReadSkillResourceTool.builder()
                .registry(fresh.registry)
                .telemetrySink(sink)
                .build();
        var tools = new ArrayList<ToolCall>(4);
        tools.add(skillTool);
        tools.add(readResourceTool);
        tools.add(buildSearchSkillsTool(fresh.registry, null, false));
        if (telemetryService != null) {
            tools.add(ReportSkillPapercutTool.builder()
                    .registry(fresh.registry)
                    .submitter(this::submitPapercut)
                    .build());
        }
        session.loadTools(tools);
        if (telemetryBuffer != null && session.agent() != null) {
            session.agent().addLifecycle(new SkillTelemetryLifecycle(fresh.registry, null, sink, null));
        }
        return fresh;
    }

    private SearchSkillsTool.SearchResponse searchControlledCatalog(SkillRegistry registry, String callerNamespace,
                                                                    String query, String namespaceFilter, int limit) {
        var outcome = catalogService.searchControlled(query, callerNamespace, namespaceFilter, limit,
                skill -> accessPolicy == null || canViewCatalogSkill(skill));
        if (outcome.degraded()) {
            return new SearchSkillsTool.SearchResponse(List.of(), List.of(), true);
        }
        var hits = outcome.skills().stream().map(scored -> {
            var skill = scored.skill();
            boolean attached = registry != null && registry.find(skill.qualifiedName()) != null;
            var manifest = SkillSdkCapabilityManifest.parse(skill.allowedTools(), skill.metadata());
            var capabilities = manifest.entries().stream()
                    .map(entry -> entry.kind() + ":" + entry.displayPath())
                    .toList();
            return new SearchSkillsTool.SearchHit(
                    skill.qualifiedName(),
                    skill.description() == null ? "" : skill.description(),
                    skill.tier() != null ? skill.tier().name() : "DOMAIN",
                    skill.namespace() == null ? "" : skill.namespace(),
                    attached,
                    capabilities);
        }).toList();
        var namespaces = outcome.namespaces().stream()
                .map(ns -> new SearchSkillsTool.NamespaceSummary(ns.namespace(), ns.matchedCount()))
                .toList();
        return new SearchSkillsTool.SearchResponse(hits, namespaces, false);
    }

    private boolean canViewCatalogSkill(SkillCatalogService.CatalogSkill skill) {
        var def = skillService.findOrNull(skill.id());
        return def != null && accessPolicy.canView(def);
    }

    private String submitPapercut(ReportSkillPapercutTool.PapercutSubmission submission) {
        var def = submission.skill().getSkillId() != null
                ? skillService.findOrNull(submission.skill().getSkillId())
                : skillService.findByQualifiedNameOrNull(submission.skill().getQualifiedName());
        if (def == null) {
            throw new NotFoundException("skill not found: " + submission.skill().getQualifiedName());
        }
        var category = SkillPapercutCategory.parse(submission.category());
        var command = new SkillTelemetryService.ReportPapercutCommand(
                category != null ? category : SkillPapercutCategory.OTHER,
                submission.summary(),
                submission.observedError(),
                submission.suggestedFix(),
                submission.skill().getDigest(),
                submission.reporterUserId(),
                submission.sessionId());
        var saved = telemetryService.recordPapercut(def, command);
        return "Papercut recorded (id=" + saved.id() + ", occurrences=" + saved.occurrenceCount()
                + ", status=" + saved.status() + ") for " + saved.qualifiedName();
    }

    static final class SessionSkillState {
        final InProcessAgentSession session;
        final Set<String> allowedIds = ConcurrentHashMap.newKeySet();
        final ConcurrentMap<String, SkillMetadata> skills = new ConcurrentHashMap<>();
        final SkillRegistry registry = new SkillRegistry();

        SessionSkillState(InProcessAgentSession session) {
            this.session = session;
        }
    }

    record ResolvedDefinitionSkills(List<String> skillIds, List<SkillMetadata> skills) {
    }
}
