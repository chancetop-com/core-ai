package ai.core.server.costalert;

import ai.core.server.channel.ChannelConfigStore;
import ai.core.server.channel.ChannelConfigView;
import ai.core.server.channel.ChannelRegistry;
import ai.core.server.domain.NotificationCategory;
import ai.core.server.domain.NotificationType;
import ai.core.server.notification.NotificationService;
import ai.core.server.trace.domain.Trace;
import com.mongodb.DuplicateKeyException;
import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.json.JSON;
import core.framework.mongo.Aggregate;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
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

    private static double doubleValue(Document row, String key) {
        var value = row.get(key);
        return value instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static long longValue(Document row, String key) {
        var value = row.get(key);
        return value instanceof Number n ? n.longValue() : 0L;
    }

    @Inject
    MongoCollection<CostAlertRule> ruleCollection;
    @Inject
    MongoCollection<CostAlertEvent> eventCollection;
    @Inject
    MongoCollection<Trace> traceCollection;
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
        List<CostAlertRule> rules = ruleCollection.find(new Query());
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
        var filters = new ArrayList<org.bson.conversions.Bson>();
        filters.add(Filters.gte("started_at", start));
        filters.add(Filters.lt("started_at", end));
        if (rule.scope == CostAlertScope.USER) {
            filters.add(Filters.eq("user_id", rule.scopeValue));
        } else if (rule.scope == CostAlertScope.AGENT) {
            filters.add(Filters.eq("agent_id", rule.scopeValue));
        }
        var aggregate = new Aggregate<Document>();
        aggregate.resultClass = Document.class;
        aggregate.pipeline = List.of(
            Aggregates.match(Filters.and(filters)),
            Aggregates.group(null,
                Accumulators.sum("cost_usd", "$cost_usd"),
                Accumulators.sum("total_tokens", "$total_tokens"),
                Accumulators.sum("input_tokens", "$input_tokens"),
                Accumulators.sum("output_tokens", "$output_tokens"),
                Accumulators.sum("call_count", 1L))
        );
        var rows = traceCollection.aggregate(aggregate);
        if (rows.isEmpty()) return null;
        var row = rows.get(0);
        return new UsageAgg(
            doubleValue(row, "cost_usd"),
            longValue(row, "total_tokens"),
            longValue(row, "input_tokens"),
            longValue(row, "output_tokens"),
            longValue(row, "call_count"));
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
        try {
            eventCollection.insert(event);
            return true;
        } catch (DuplicateKeyException e) {
            LOGGER.debug("cost alert already fired, ruleId={}, date={}, scope={}", rule.id, date, rule.scopeValue);
            return false;
        }
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
        outbound.sendText(null, recipient, message, null, channel.config);
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
