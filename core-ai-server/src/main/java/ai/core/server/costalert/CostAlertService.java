package ai.core.server.costalert;

import ai.core.server.channel.ChannelConfigStore;
import ai.core.server.channel.ChannelConfigView;
import ai.core.server.channel.ChannelMessage;
import ai.core.server.channel.ChannelRegistry;
import ai.core.server.domain.NotificationCategory;
import ai.core.server.domain.NotificationType;
import ai.core.server.notification.NotificationService;
import ai.core.server.trace.TraceAggregation;
import ai.core.server.trace.TraceQuery;
import ai.core.server.trace.TraceStore;
import core.framework.inject.Inject;
import core.framework.json.JSON;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * Evaluates cost alert rules against daily trace aggregates and delivers
 * notifications to in-app users and configured channels.
 *
 * @author stephen
 */
public class CostAlertService {
    private static final Logger LOGGER = LoggerFactory.getLogger(CostAlertService.class);
    private static final ZoneId UTC = ZoneId.of("UTC");

    private static String formatTokens(long tokens) {
        if (tokens >= 1_000_000) return String.format("%.1fM", tokens / 1_000_000.0);
        if (tokens >= 1_000) return String.format("%.1fK", tokens / 1_000.0);
        return String.valueOf(tokens);
    }

    @Inject
    CostAlertRuleStore ruleStore;
    @Inject
    CostAlertEventStore eventStore;
    @Inject
    TraceStore traceStore;
    @Inject
    ChannelConfigStore channelConfigStore;
    @Inject
    ChannelRegistry channelRegistry;
    @Inject
    NotificationService notificationService;

    /**
     * Check both the previous full day (final value) and today so far (progress value).
     */
    public void check(LocalDate today) {
        var yesterday = today.minusDays(1);
        List<CostAlertRule> rules = ruleStore.list();
        for (var rule : rules) {
            if (!Boolean.TRUE.equals(rule.enabled)) continue;
            checkWindow(rule, yesterday, yesterday.atStartOfDay(UTC), today.atStartOfDay(UTC));
            checkWindow(rule, today, today.atStartOfDay(UTC), ZonedDateTime.now(UTC));
        }
        LOGGER.info("cost alert check finished, date={}, rules={}", today, rules.size());
    }

    private void checkWindow(CostAlertRule rule, LocalDate date, ZonedDateTime start, ZonedDateTime end) {
        var usage = aggregate(rule, start, end);
        if (usage == null) return;
        double actual = switch (rule.metric) {
            case COST_USD -> usage.costUsd;
            case TOTAL_TOKENS -> usage.totalTokens;
            case CALL_COUNT -> usage.callCount;
        };
        if (actual < rule.threshold) return;
        if (tryInsertEvent(rule, date, actual, usage)) {
            send(rule, date, actual, usage);
        }
    }

    private UsageAgg aggregate(CostAlertRule rule, ZonedDateTime start, ZonedDateTime end) {
        var query = new TraceQuery();
        query.startFrom = start;
        query.startBefore = end;
        if (rule.scope == CostAlertScope.USER) {
            query.userId = rule.scopeValue;
        } else if (rule.scope == CostAlertScope.AGENT) {
            query.agentId = rule.scopeValue;
        }
        var aggregation = new TraceAggregation();
        aggregation.query = query;
        var rows = traceStore.aggregate(aggregation);
        if (rows.isEmpty()) return null;
        var row = rows.getFirst();
        return new UsageAgg(
            row.costUsd != null ? row.costUsd : 0.0,
            row.totalTokens != null ? row.totalTokens : 0L,
            row.inputTokens != null ? row.inputTokens : 0L,
            row.outputTokens != null ? row.outputTokens : 0L,
            row.count != null ? row.count : 0L);
    }

    private boolean tryInsertEvent(CostAlertRule rule, LocalDate date, double actual, UsageAgg usage) {
        var event = new CostAlertEvent();
        event.id = new ObjectId().toHexString();
        event.ruleId = rule.id;
        event.ruleName = rule.name;
        event.date = date.atStartOfDay(UTC);
        event.scope = rule.scope.name();
        event.scopeValue = rule.scopeValue == null ? "" : rule.scopeValue;
        event.metric = rule.metric.name();
        event.threshold = rule.threshold;
        event.actualValue = actual;
        event.detail = JSON.toJSON(Map.of(
            "input_tokens", usage.inputTokens,
            "output_tokens", usage.outputTokens,
            "total_tokens", usage.totalTokens,
            "call_count", usage.callCount));
        event.createdAt = ZonedDateTime.now(UTC);
        if (eventStore.insertIfAbsent(event)) return true;
        LOGGER.debug("cost alert already fired, ruleId={}, date={}, scope={}", rule.id, date, rule.scopeValue);
        return false;
    }

    @SuppressWarnings("unchecked")
    private void send(CostAlertRule rule, LocalDate date, double actual, UsageAgg usage) {
        var message = buildMessage(rule, date, actual, usage);
        List<Map<String, Object>> targets;
        try {
            targets = (List<Map<String, Object>>) JSON.fromJSON(List.class, rule.targets);
        } catch (Exception e) {
            LOGGER.warn("cost alert rule has invalid targets json, ruleId={}", rule.id, e);
            return;
        }
        for (var target : targets) {
            try {
                if ("notification".equals(target.get("type"))) {
                    var userId = (String) target.get("userId");
                    if (userId == null || userId.isBlank()) continue;
                    notificationService.create(userId, NotificationCategory.SYSTEM, NotificationType.COST_ALERT,
                        "Cost Alert: " + rule.name, message, new NotificationService.CreateContext(null, null));
                } else if ("channel".equals(target.get("type"))) {
                    sendToChannel(rule, target, message);
                } else {
                    LOGGER.warn("unknown cost alert target type: {}", target.get("type"));
                }
            } catch (Exception e) {
                LOGGER.warn("failed to send cost alert, ruleId={}, target={}", rule.id, target, e);
            }
        }
    }

    private void sendToChannel(CostAlertRule rule, Map<String, Object> target, String message) {
        var channelId = (String) target.get("channelId");
        var recipient = (String) target.get("recipient");
        if (channelId == null || channelId.isBlank()) return;
        if (recipient == null || recipient.isBlank()) return;
        ChannelConfigView channel = channelConfigStore.load(channelId);
        if (channel == null) {
            LOGGER.warn("cost alert channel missing, ruleId={}, channelId={}", rule.id, channelId);
            return;
        }
        if (!Boolean.TRUE.equals(channel.enabled)) {
            LOGGER.warn("cost alert channel disabled, ruleId={}, channelId={}", rule.id, channelId);
            return;
        }
        var outbound = channelRegistry.outbound(channel.channelType);
        outbound.sendMessage(ChannelMessage.text(message), channelId, recipient, recipient, null, channel.config);
    }

    private String buildMessage(CostAlertRule rule, LocalDate date, double actual, UsageAgg usage) {
        return String.join("\n",
            "⚠️ Cost Alert: " + rule.name,
            "Date: " + date + " (UTC)",
            "Scope: " + scopeLabel(rule),
            "Metric: " + metricValue(rule.metric, actual) + " (threshold: " + metricValue(rule.metric, rule.threshold) + ")",
            "Usage: " + usage.callCount + " calls, input " + formatTokens(usage.inputTokens)
                + " / output " + formatTokens(usage.outputTokens) + " tokens");
    }

    private String scopeLabel(CostAlertRule rule) {
        return switch (rule.scope) {
            case GLOBAL -> "all users";
            case USER -> "user " + rule.scopeValue;
            case AGENT -> "agent " + rule.scopeValue;
        };
    }

    private String metricValue(CostAlertMetric metric, double value) {
        return switch (metric) {
            case COST_USD -> String.format("$%.2f", value);
            case TOTAL_TOKENS -> formatTokens((long) value);
            case CALL_COUNT -> String.format("%,d calls", (long) value);
        };
    }

    record UsageAgg(double costUsd, long totalTokens, long inputTokens, long outputTokens, long callCount) {
    }
}

