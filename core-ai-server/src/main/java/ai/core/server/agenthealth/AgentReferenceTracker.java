package ai.core.server.agenthealth;

import ai.core.server.agent.AgentDefinitionStore;
import ai.core.server.apitoolhub.ApiToolCatalogService;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.NotificationCategory;
import ai.core.server.domain.NotificationType;
import ai.core.server.notification.NotificationService;
import ai.core.server.quality.QualityHashes;
import core.framework.inject.Inject;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Edge-triggered reference detection and the notification ledger. The badge is computed by
 * {@link AgentHealthService}; this tracker only answers "has this episode been notified?" and keeps
 * the sweep/backfill safety net.
 *
 * @author core-ai
 */
public class AgentReferenceTracker {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentReferenceTracker.class);
    private static final int RECONCILE_BATCH = 500;
    private static final int RECONCILE_MAX_PER_RUN = 5000;
    private static final long RECONCILE_BUDGET_NANOS = Duration.ofSeconds(5).toNanos();
    private static final int ACTIVE_CHECK_LIMIT = 1000;

    @Inject
    AgentDefinitionStore agentDefinitionStore;
    @Inject
    AgentHealthService healthService;
    @Inject
    ApiToolCatalogService apiToolCatalogService;
    @Inject
    AgentReferenceIssueStore issueStore;
    @Inject
    NotificationService notificationService;

    public void onSkillDeleted(String skillId, String skillName, String actorUserId) {
        if (skillId == null || issueStore == null) return;
        try {
            var agents = agentDefinitionStore.findReferencingAgents(skillId);
            if (agents.isEmpty()) return;
            LOGGER.info("skill deleted, refreshing agent references, skillId={}, agents={}", skillId, agents.size());
            var names = new HashMap<String, String>();
            if (skillName != null && !skillName.isBlank()) names.put(skillId, skillName);
            refreshIssues(agents, names, notificationsEnabled());
        } catch (RuntimeException e) {
            LOGGER.error("failed to refresh agent references after skill deletion, skillId={}", skillId, e);
        }
    }

    public void onAgentSaved(AgentDefinition entity, String actorUserId) {
        if (entity == null || issueStore == null) return;
        try {
            refreshIssues(List.of(entity), Map.of(), notificationsEnabled());
        } catch (RuntimeException e) {
            LOGGER.error("failed to refresh agent references after save, agentId={}", entity.id, e);
        }
    }

    public void onAgentDeleted(String agentId) {
        if (agentId == null || issueStore == null) return;
        try {
            issueStore.resolveByAgent(agentId, "AGENT_DELETED", ZonedDateTime.now());
        } catch (RuntimeException e) {
            LOGGER.error("failed to resolve agent reference issues after delete, agentId={}", agentId, e);
        }
    }

    public void onApiCatalogReloaded() {
        if (issueStore == null) return;
        try {
            var agents = agentDefinitionStore.findAgentsWithApiRefs();
            if (agents.isEmpty()) return;
            LOGGER.info("api catalog reloaded, refreshing agent references, agents={}", agents.size());
            refreshIssues(agents, Map.of(), notificationsEnabled());
        } catch (RuntimeException e) {
            LOGGER.error("failed to refresh agent references after API catalog reload", e);
        }
    }

    /**
     * Full safety-net sweep. Existing issues are recorded silently until one sweep completes; that is
     * what keeps the first deployment from notifying every historical broken reference.
     */
    public void reconcile() {
        if (issueStore == null) return;
        try {
            var state = issueStore.state();
            boolean notify = Boolean.TRUE.equals(state.backfillDone);
            String cursor = state.cursor;
            int processed = 0;
            boolean complete = false;
            long started = System.nanoTime();
            while (processed < RECONCILE_MAX_PER_RUN
                && System.nanoTime() - started < RECONCILE_BUDGET_NANOS) {
                var page = agentDefinitionStore.listReferenceCandidates(cursor, RECONCILE_BATCH);
                if (page.isEmpty()) {
                    complete = true;
                    break;
                }
                refreshIssues(page, Map.of(), notify);
                cursor = page.get(page.size() - 1).id;
                processed += page.size();
                if (page.size() < RECONCILE_BATCH) {
                    complete = true;
                    break;
                }
            }

            var now = ZonedDateTime.now();
            if (complete) {
                state.backfillDone = Boolean.TRUE;
                state.cursor = null;
                state.lastFullSweepAt = now;
            } else {
                state.cursor = cursor;
            }
            state.lastRunAt = now;
            issueStore.saveState(state);
            reconcileActiveIssues();
            LOGGER.debug("agent health reconcile finished, agents={}, complete={}, backfillDone={}",
                processed, complete, state.backfillDone);
        } catch (RuntimeException e) {
            LOGGER.error("agent health reconcile failed", e);
        }
    }

    private Boolean notificationsEnabled() {
        try {
            return Boolean.TRUE.equals(issueStore.state().backfillDone);
        } catch (RuntimeException e) {
            // unknown is not the same as disabled: leave newly detected issues unnotified for a later retry
            return null;
        }
    }

    private void refreshIssues(List<AgentDefinition> agents, Map<String, String> skillNameOverrides, Boolean notify) {
        if (issueStore == null || agents == null || agents.isEmpty()) return;
        var now = ZonedDateTime.now();
        var skillIds = new LinkedHashSet<String>();
        for (var agent : agents) skillIds.addAll(healthService.skillIds(agent));
        var skills = healthService.probeSkills(skillIds);
        var catalog = apiToolCatalogService == null ? null : apiToolCatalogService.successSnapshot();
        var agentIds = agents.stream().map(agent -> agent.id).toList();
        var activeByAgent = issueStore.listActiveByAgentIds(agentIds).stream()
            .collect(Collectors.groupingBy(issue -> issue.agentId));

        for (var agent : agents) {
            try {
                refreshAgentIssues(agent, activeByAgent.getOrDefault(agent.id, List.of()),
                    skills, catalog, skillNameOverrides, now);
            } catch (RuntimeException e) {
                LOGGER.warn("failed to refresh reference issues, agentId={}: {}", agent.id, e.getMessage());
            }
        }
        notifyUnnotified(agents, agentIds, notify, now);
    }

    private void refreshAgentIssues(AgentDefinition agent, List<AgentReferenceIssue> active,
                                    AgentHealthService.SkillProbe skills,
                                    ApiToolCatalogService.CatalogSnapshot catalog,
                                    Map<String, String> skillNameOverrides, ZonedDateTime now) {
        var refs = healthService.collectRefs(agent);
        var broken = new LinkedHashMap<AgentHealthService.RefKey, String>();
        for (var key : refs.keySet()) {
            var check = healthService.check(key, skills, catalog);
            if (check.state() == AgentHealthService.RefState.BROKEN) broken.put(key, check.reason());
        }
        var existingNames = new HashMap<String, String>();
        for (var issue : active) {
            if (issue.refName != null) {
                existingNames.put(healthService.issueKey(agent.id, issue.refType, issue.refId), issue.refName);
            }
        }

        for (var entry : broken.entrySet()) {
            var key = entry.getKey();
            var issue = new AgentReferenceIssue();
            issue.agentId = agent.id;
            issue.agentOwnerId = agent.userId;
            issue.agentName = agent.name;
            issue.refType = key.type();
            issue.refId = key.id();
            issue.refName = healthService.issueName(key, skills, skillNameOverrides,
                existingNames.get(healthService.issueKey(agent.id, key.type(), key.id())));
            issue.reason = entry.getValue();
            issue.sources = healthService.sourceNames(refs.get(key), true);
            issue.firstDetectedAt = now;
            issue.lastSeenAt = now;
            issueStore.upsertActive(issue);
        }

        var removed = new ArrayList<String>();
        var restored = new ArrayList<String>();
        for (var issue : active) {
            var key = new AgentHealthService.RefKey(issue.refType, issue.refId);
            if (broken.containsKey(key)) continue;
            if (!healthService.probeVerified(key, skills, catalog)) continue;
            if (refs.containsKey(key)) restored.add(issue.id);
            else removed.add(issue.id);
        }
        if (!removed.isEmpty()) issueStore.resolve(removed, "REMOVED", now);
        if (!restored.isEmpty()) issueStore.resolve(restored, "RESTORED", now);
    }

    private void notifyUnnotified(List<AgentDefinition> agents, List<String> agentIds, Boolean notify, ZonedDateTime now) {
        var unnotified = issueStore.listActiveByAgentIds(agentIds).stream()
            .filter(issue -> issue.notifiedAt == null)
            .collect(Collectors.groupingBy(issue -> issue.agentId));
        for (var agent : agents) {
            var issues = unnotified.getOrDefault(agent.id, List.of());
            if (issues.isEmpty()) continue;
            if (Boolean.FALSE.equals(notify)) {
                var ids = issues.stream().map(issue -> issue.id).toList();
                issueStore.markNotified(ids, now);
                continue;
            }
            if (notify == null) continue;
            try {
                notifyAgent(agent, issues, now);
            } catch (RuntimeException e) {
                LOGGER.error("failed to notify agent reference issues, agentId={}", agent.id, e);
            }
        }
    }

    private void notifyAgent(AgentDefinition agent, List<AgentReferenceIssue> issues, ZonedDateTime now) {
        var ids = issues.stream().map(issue -> issue.id).sorted().toList();
        if (agent.userId == null || notificationService == null) {
            issueStore.markNotified(ids, now);
            return;
        }
        var name = agent.name == null ? agent.id : agent.name;
        var title = "Agent \"" + name + "\" needs attention";
        var message = notificationMessage(issues);
        var dedupeKey = "agent-health:" + agent.id + ":" + agent.userId + ":"
            + QualityHashes.textHash(String.join(",", ids));
        boolean inserted = notificationService.createIfAbsent(agent.userId, NotificationCategory.AGENT,
            NotificationType.AGENT_ATTENTION, title, message,
            new NotificationService.CreateContext(agent.id, null, dedupeKey));
        if (inserted) {
            LOGGER.info("agent attention notification created, agentId={}, issues={}", agent.id, ids.size());
        } else {
            LOGGER.debug("agent attention notification already exists, agentId={}", agent.id);
        }
        issueStore.markNotified(ids, now);
    }

    @SuppressFBWarnings("SEO_SUBOPTIMAL_EXPRESSION_ORDER")
    private String notificationMessage(List<AgentReferenceIssue> issues) {
        var message = new StringBuilder(256);
        int count = issues.size();
        message.append(count == 1 ? "1 reference is broken: " : count + " references are broken: ");
        for (int i = 0; i < issues.size() && i < 5; i++) {
            if (i > 0) message.append("; ");
            var issue = issues.get(i);
            message.append(issue.refName == null ? issue.refId : issue.refName)
                .append(' ').append(reasonPhrase(issue.reason));
        }
        if (issues.size() > 5) message.append("; and ").append(issues.size() - 5).append(" more");
        message.append(". Open the agent to remove the broken references or republish.");
        return message.toString();
    }

    private String reasonPhrase(String reason) {
        return switch (reason == null ? "" : reason) {
            case "SKILL_NOT_FOUND" -> "was deleted";
            case "API_TOOL_NOT_FOUND" -> "is no longer available";
            case "MALFORMED_REFERENCE" -> "is malformed";
            default -> "is unavailable";
        };
    }

    private void reconcileActiveIssues() {
        var active = issueStore.listActive(ACTIVE_CHECK_LIMIT, null);
        if (active.isEmpty()) return;
        var skills = healthService.probeSkills(active.stream()
            .filter(issue -> "SKILL".equals(issue.refType))
            .map(issue -> issue.refId)
            .collect(Collectors.toSet()));
        var catalog = apiToolCatalogService == null ? null : apiToolCatalogService.successSnapshot();
        var removed = new ArrayList<String>();
        var restored = new ArrayList<String>();
        var deleted = new ArrayList<String>();
        for (var issue : active) {
            var agent = agentDefinitionStore.get(issue.agentId).orElse(null);
            if (agent == null) {
                deleted.add(issue.id);
                continue;
            }
            var key = new AgentHealthService.RefKey(issue.refType, issue.refId);
            if (!healthService.probeVerified(key, skills, catalog)) continue;
            var refs = healthService.collectRefs(agent);
            if (!refs.containsKey(key)) removed.add(issue.id);
            else if (healthService.check(key, skills, catalog).state() == AgentHealthService.RefState.OK) {
                restored.add(issue.id);
            }
        }
        var now = ZonedDateTime.now();
        if (!deleted.isEmpty()) issueStore.resolve(deleted, "AGENT_DELETED", now);
        if (!removed.isEmpty()) issueStore.resolve(removed, "REMOVED", now);
        if (!restored.isEmpty()) issueStore.resolve(restored, "RESTORED", now);
    }
}
