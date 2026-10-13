package ai.core.server.comparison;

import ai.core.api.server.comparison.AgentComparisonView;
import ai.core.api.server.comparison.ComparisonCountsView;
import ai.core.api.server.comparison.ComparisonSessionPairView;
import ai.core.api.server.comparison.ComparisonVariantView;
import ai.core.api.server.comparison.ComparisonVoteView;
import ai.core.server.comparison.domain.AgentComparison;
import ai.core.server.comparison.domain.ComparisonStatus;
import ai.core.server.comparison.domain.ComparisonVariant;
import ai.core.server.comparison.domain.ComparisonVote;
import ai.core.server.comparison.domain.ComparisonVoteChoice;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.domain.ChatMessage;
import ai.core.server.domain.ChatSession;
import ai.core.server.domain.ToolRef;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Assembles comparison API views, including the blind projection for non-manager participants.
 *
 * @author stephen
 */
public class ComparisonViewAssembler {
    private static final String BLIND_LABEL_A = "版本 1";
    private static final String BLIND_LABEL_B = "版本 2";

    public static ComparisonVoteView toVoteView(ComparisonVote vote) {
        var view = new ComparisonVoteView();
        view.id = vote.id;
        view.comparisonId = vote.comparisonId;
        view.pairId = vote.pairId;
        view.sessionA = vote.sessionA;
        view.sessionB = vote.sessionB;
        view.turn = vote.turn;
        view.chosen = vote.chosen != null ? vote.chosen.name() : null;
        view.comment = vote.comment;
        view.messageA = vote.messageA;
        view.messageB = vote.messageB;
        view.userId = vote.userId;
        view.createdAt = vote.createdAt;
        return view;
    }

    static String diffSummary(AgentPublishedConfig from, AgentPublishedConfig to) {
        if (from == null || to == null) return null;
        var parts = new ArrayList<String>();
        appendIfChanged(parts, "model", from.model, to.model);
        appendIfChanged(parts, "multimodal_model", from.multiModalModel, to.multiModalModel);
        appendIfChanged(parts, "thinking_effort", from.thinkingEffort, to.thinkingEffort);
        appendIfChanged(parts, "temperature", from.temperature, to.temperature);
        appendIfChanged(parts, "max_turns", from.maxTurns, to.maxTurns);
        appendIfChanged(parts, "enable_memory", from.enableMemory, to.enableMemory);
        if (!Objects.equals(from.systemPrompt, to.systemPrompt) || !Objects.equals(from.systemPromptId, to.systemPromptId)) {
            parts.add("system_prompt changed");
        }
        appendListDiff(parts, "skill", from.skillIds, to.skillIds);
        appendListDiff(parts, "tool", toolIds(from.tools), toolIds(to.tools));
        appendListDiff(parts, "subagent", from.subAgentIds, to.subAgentIds);
        return parts.isEmpty() ? "same as the other variant" : String.join("; ", parts);
    }

    private static ComparisonCountsView countsOf(List<ComparisonSessionPairView> pairs, List<ComparisonVoteView> votes) {
        var counts = new ComparisonCountsView();
        counts.votesA = countChoice(votes, ComparisonVoteChoice.A);
        counts.votesB = countChoice(votes, ComparisonVoteChoice.B);
        counts.ties = countChoice(votes, ComparisonVoteChoice.TIE);
        counts.neithers = countChoice(votes, ComparisonVoteChoice.NEITHER);
        counts.votes = votes.size();
        counts.turns = pairs.stream().mapToInt(pair -> pair.turnCount != null ? pair.turnCount : 0).sum();
        return counts;
    }

    private static int countChoice(List<ComparisonVoteView> votes, ComparisonVoteChoice choice) {
        var name = choice.name();
        return (int) votes.stream().filter(vote -> name.equals(vote.chosen)).count();
    }

    private static String summary(AgentComparison comparison, String variantKey) {
        if (comparison.variantA == null || comparison.variantB == null) return null;
        var self = ComparisonVariantDefinitionFactory.variantOf(comparison, variantKey);
        var other = ComparisonVariantDefinitionFactory.variantOf(comparison, "a".equals(variantKey) ? "b" : "a");
        if (self == null || other == null) return null;
        return diffSummary(other.config, self.config);
    }

    private static String managerLabel(ComparisonVariant variant, String variantKey) {
        if (variant != null && variant.label != null && !variant.label.isBlank()) return variant.label;
        return "a".equals(variantKey) ? "A" : "B";
    }

    private static void appendIfChanged(List<String> parts, String name, Object from, Object to) {
        if (!Objects.equals(from, to)) parts.add(name + ": " + from + " → " + to);
    }

    private static void appendListDiff(List<String> parts, String name, List<String> from, List<String> to) {
        var base = from != null ? from : List.<String>of();
        var target = to != null ? to : List.<String>of();
        for (var item : target) {
            if (!base.contains(item)) parts.add("+" + name + ": " + item);
        }
        for (var item : base) {
            if (!target.contains(item)) parts.add("-" + name + ": " + item);
        }
    }

    private static List<String> toolIds(List<ToolRef> tools) {
        if (tools == null) return List.of();
        return tools.stream().map(tool -> tool.id).toList();
    }

    @Inject
    MongoCollection<ComparisonVote> voteCollection;
    @Inject
    MongoCollection<ChatSession> chatSessionCollection;
    @Inject
    MongoCollection<ChatMessage> chatMessageCollection;
    @Inject
    MongoCollection<AgentDefinition> agentDefinitionCollection;

    public AgentComparisonView toListView(AgentComparison comparison) {
        var view = baseView(comparison);
        view.variantA = variantView(comparison.variantA, "a", managerLabel(comparison.variantA, "a"), false, null);
        view.variantB = variantView(comparison.variantB, "b", managerLabel(comparison.variantB, "b"), false, null);
        return view;
    }

    public AgentComparisonView toDetailView(AgentComparison comparison, boolean manager) {
        var view = baseView(comparison);
        view.agentChanged = agentChanged(comparison);
        if (manager) {
            view.variantA = variantView(comparison.variantA, "a", managerLabel(comparison.variantA, "a"), true, summary(comparison, "a"));
            view.variantB = variantView(comparison.variantB, "b", managerLabel(comparison.variantB, "b"), true, summary(comparison, "b"));
            view.pairs = pairsOf(comparison);
            view.votes = votesOf(comparison.id);
            view.counts = countsOf(view.pairs, view.votes);
            return view;
        }
        view.blind = Boolean.TRUE;
        view.versionAId = null;
        view.versionBId = null;
        view.variantA = variantView(comparison.variantA, "a", BLIND_LABEL_A, false, null);
        view.variantB = variantView(comparison.variantB, "b", BLIND_LABEL_B, false, null);
        view.counts = countsOf(List.of(), votesOf(comparison.id));
        return view;
    }

    public List<ComparisonSessionPairView> pairsOf(AgentComparison comparison) {
        var query = new Query();
        query.filter = Filters.eq("comparison_id", comparison.id);
        var grouped = new LinkedHashMap<String, List<ChatSession>>();
        for (var session : chatSessionCollection.find(query)) {
            if (session.comparisonPairId == null) continue;
            grouped.computeIfAbsent(session.comparisonPairId, key -> new ArrayList<>()).add(session);
        }
        var pairs = new ArrayList<ComparisonSessionPairView>(grouped.size());
        for (var entry : grouped.entrySet()) {
            pairs.add(toPairView(comparison.id, entry.getKey(), entry.getValue()));
        }
        return pairs;
    }

    public List<ComparisonVoteView> votesOf(String comparisonId) {
        var query = new Query();
        query.filter = Filters.eq("comparison_id", comparisonId);
        query.sort = Sorts.ascending("created_at");
        return voteCollection.find(query).stream().map(ComparisonViewAssembler::toVoteView).toList();
    }

    private AgentComparisonView baseView(AgentComparison comparison) {
        var view = new AgentComparisonView();
        view.id = comparison.id;
        view.agentId = comparison.agentId;
        view.agentName = comparison.agentName;
        view.status = comparison.status != null ? comparison.status.name() : null;
        view.createdBy = comparison.createdBy;
        view.participantUserIds = comparison.participantUserIds;
        view.blind = Boolean.FALSE;
        view.versionAId = comparison.versionAId;
        view.versionBId = comparison.versionBId;
        view.budgetUsd = comparison.budgetUsd;
        view.startedAt = comparison.startedAt;
        view.endedAt = comparison.endedAt;
        view.adoptedVariant = comparison.adoptedVariant;
        view.createdAt = comparison.createdAt;
        view.updatedAt = comparison.updatedAt;
        return view;
    }

    private ComparisonVariantView variantView(ComparisonVariant variant, String variantKey, String label,
                                              boolean withConfig, String diff) {
        var view = new ComparisonVariantView();
        view.variant = variantKey;
        view.label = label;
        view.summary = diff;
        if (variant == null) return view;
        var config = variant.config;
        if (config != null) view.model = config.model;
        if (withConfig) {
            view.config = ComparisonViews.toConfigView(config);
            view.effectiveTools = variant.effectiveTools != null
                ? variant.effectiveTools.stream().map(ComparisonToolFreeze::toView).toList()
                : null;
        }
        return view;
    }

    private ComparisonSessionPairView toPairView(String comparisonId, String pairId, List<ChatSession> rows) {
        ChatSession sessionA = null;
        ChatSession sessionB = null;
        ZonedDateTime createdAt = null;
        for (var row : rows) {
            if ("a".equals(row.comparisonVariant)) sessionA = row;
            if ("b".equals(row.comparisonVariant)) sessionB = row;
            if (row.createdAt != null && (createdAt == null || row.createdAt.isBefore(createdAt))) createdAt = row.createdAt;
        }
        var view = new ComparisonSessionPairView();
        view.pairId = pairId;
        view.sessionA = sessionA != null ? sessionA.id : null;
        view.sessionB = sessionB != null ? sessionB.id : null;
        var reference = sessionA != null ? sessionA : sessionB;
        view.userId = reference != null ? reference.userId : null;
        view.createdAt = createdAt;
        view.turnCount = sessionA != null ? (int) chatMessageCollection.count(Filters.and(
            Filters.eq("session_id", sessionA.id), Filters.eq("role", "user"))) : 0;
        view.voteCount = sessionA != null ? (int) voteCollection.count(Filters.and(
            Filters.eq("comparison_id", comparisonId), Filters.eq("session_a", sessionA.id))) : 0;
        return view;
    }

    private boolean agentChanged(AgentComparison comparison) {
        // the fingerprint only exists once the test started; a draft has nothing to drift from
        if (comparison.status == null || comparison.status == ComparisonStatus.DRAFT) return false;
        var agent = agentDefinitionCollection.get(comparison.agentId).orElse(null);
        return agent == null || !Objects.equals(agent.publishedAt, comparison.agentPublishedAt);
    }
}
