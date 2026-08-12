package ai.core.server.costalert;

import ai.core.server.channel.ChannelConfigStore;
import ai.core.server.channel.ChannelConfigView;
import ai.core.server.channel.ChannelOutboundAdapter;
import ai.core.server.channel.ChannelRegistry;
import ai.core.server.domain.NotificationCategory;
import ai.core.server.domain.NotificationType;
import ai.core.server.notification.NotificationService;
import ai.core.server.trace.domain.Trace;
import com.mongodb.DuplicateKeyException;
import core.framework.json.JSON;
import core.framework.mongo.Aggregate;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cost alert evaluation: threshold comparison, per-window dedup and target dispatch.
 *
 * @author stephen
 */
class CostAlertServiceTest {
    private static final org.bson.codecs.configuration.CodecRegistry CODEC_REGISTRY =
        org.bson.codecs.configuration.CodecRegistries.fromRegistries(
            org.bson.codecs.configuration.CodecRegistries.fromCodecs(
                new core.framework.mongo.impl.ZonedDateTimeCodec(),
                new core.framework.mongo.impl.LocalDateCodec(),
                new core.framework.mongo.impl.LocalDateTimeCodec()),
            com.mongodb.MongoClientSettings.getDefaultCodecRegistry());

    private static String notificationTargets() {
        return JSON.toJSON(List.of(Map.of("type", "notification", "userId", "user-1")));
    }

    private static Document row(double costUsd) {
        return new Document("cost_usd", costUsd)
            .append("total_tokens", 2000L)
            .append("input_tokens", 1500L)
            .append("output_tokens", 500L)
            .append("call_count", 7L);
    }

    private static boolean filtersBy(Aggregate<?> aggregate, String field, String value) {
        var doc = aggregate.pipeline.get(0).toBsonDocument(org.bson.BsonDocument.class, CODEC_REGISTRY);
        var match = (org.bson.BsonDocument) doc.get("$match");
        if (match.containsKey("$and")) {
            var merged = new org.bson.BsonDocument();
            for (var clause : match.getArray("$and")) {
                merged.putAll((org.bson.BsonDocument) clause);
            }
            match = merged;
        }
        return match.containsKey(field) && value.equals(match.getString(field).getValue());
    }

    private final MongoCollection<CostAlertRule> ruleCollection;
    private final MongoCollection<CostAlertEvent> eventCollection;
    private final MongoCollection<Trace> traceCollection;
    private final ChannelConfigStore channelConfigStore;
    private final ChannelRegistry channelRegistry;
    private final NotificationService notificationService;
    private final ChannelOutboundAdapter outboundAdapter;
    private final CostAlertService service;

    CostAlertServiceTest() {
        @SuppressWarnings("unchecked")
        MongoCollection<CostAlertRule> rules = mock(MongoCollection.class);
        ruleCollection = rules;
        @SuppressWarnings("unchecked")
        MongoCollection<CostAlertEvent> events = mock(MongoCollection.class);
        eventCollection = events;
        @SuppressWarnings("unchecked")
        MongoCollection<Trace> traces = mock(MongoCollection.class);
        traceCollection = traces;
        channelConfigStore = mock(ChannelConfigStore.class);
        channelRegistry = mock(ChannelRegistry.class);
        notificationService = mock(NotificationService.class);
        outboundAdapter = mock(ChannelOutboundAdapter.class);

        service = new CostAlertService();
        service.ruleCollection = ruleCollection;
        service.eventCollection = eventCollection;
        service.traceCollection = traceCollection;
        service.channelConfigStore = channelConfigStore;
        service.channelRegistry = channelRegistry;
        service.notificationService = notificationService;

        when(ruleCollection.find(any(Query.class))).thenReturn(List.of());
    }

    @Test
    void belowThresholdDoesNotFire() {
        rule(CostAlertMetric.COST_USD, CostAlertScope.GLOBAL, "", 100.0, notificationTargets());
        when(traceCollection.aggregate(any())).thenReturn(List.of(row(50.0)));

        service.check(LocalDate.of(2026, 8, 12));

        verify(eventCollection, never()).insert(any(CostAlertEvent.class));
        verify(notificationService, never()).create(any(), any(), any(), any(), any(), any());
    }

    @Test
    void costExceedsThresholdFiresForYesterdayAndToday() {
        rule(CostAlertMetric.COST_USD, CostAlertScope.GLOBAL, "", 100.0, notificationTargets());
        when(traceCollection.aggregate(any())).thenReturn(List.of(row(150.0)));

        service.check(LocalDate.of(2026, 8, 12));

        // one event per window (yesterday full day + today so far), each with distinct date
        verify(eventCollection, times(2)).insert(any(CostAlertEvent.class));
        verify(notificationService, times(2)).create(
            eq("user-1"), eq(NotificationCategory.SYSTEM), eq(NotificationType.COST_ALERT), anyString(), contains("$150.00"), any());
    }

    @Test
    void duplicateEventSkipsResend() {
        rule(CostAlertMetric.COST_USD, CostAlertScope.GLOBAL, "", 100.0, notificationTargets());
        when(traceCollection.aggregate(any())).thenReturn(List.of(row(150.0)));
        doThrow(mock(DuplicateKeyException.class)).when(eventCollection).insert(any(CostAlertEvent.class));

        service.check(LocalDate.of(2026, 8, 12));

        verify(notificationService, never()).create(any(), any(), any(), any(), any(), any());
    }

    @Test
    void userScopeAggregatesByUserId() {
        rule(CostAlertMetric.COST_USD, CostAlertScope.USER, "u-1", 100.0, notificationTargets());
        // aggregate result depends on the user filter: only a user-scoped match returns an over-threshold row
        when(traceCollection.aggregate(any())).thenAnswer(inv ->
            filtersBy(inv.getArgument(0), "user_id", "u-1") ? List.of(row(150.0)) : List.of(row(50.0)));

        service.check(LocalDate.of(2026, 8, 12));

        verify(notificationService, times(2)).create(any(), any(), any(), any(), any(), any());
    }

    @Test
    void agentScopeAggregatesByAgentId() {
        rule(CostAlertMetric.TOTAL_TOKENS, CostAlertScope.AGENT, "agent-1", 1000.0, notificationTargets());
        when(traceCollection.aggregate(any())).thenAnswer(inv ->
            filtersBy(inv.getArgument(0), "agent_id", "agent-1") ? List.of(row(2000.0)) : List.of(row(500.0)));

        service.check(LocalDate.of(2026, 8, 12));

        verify(notificationService, times(2)).create(any(), any(), any(), any(), any(), any());
    }

    @Test
    void channelTargetSendsViaOutboundAdapter() {
        var channel = new ChannelConfigView();
        channel.channelId = "c1";
        channel.channelType = "slack";
        channel.enabled = Boolean.TRUE;
        channel.config = Map.of("bot_token", "x");
        when(channelConfigStore.load("c1")).thenReturn(channel);
        when(channelRegistry.outbound("slack")).thenReturn(outboundAdapter);

        rule(CostAlertMetric.COST_USD, CostAlertScope.GLOBAL, "", 100.0,
            JSON.toJSON(List.of(Map.of("type", "channel", "channelId", "c1", "recipient", "C123"))));
        when(traceCollection.aggregate(any())).thenReturn(List.of(row(150.0)));

        service.check(LocalDate.of(2026, 8, 12));

        verify(outboundAdapter, times(2)).sendText(isNull(), eq("C123"), contains("Cost Alert"), isNull(), eq(channel.config));
    }

    @Test
    void disabledChannelSkipped() {
        var channel = new ChannelConfigView();
        channel.channelId = "c1";
        channel.channelType = "slack";
        channel.enabled = Boolean.FALSE;
        channel.config = Map.of("bot_token", "x");
        when(channelConfigStore.load("c1")).thenReturn(channel);
        when(channelRegistry.outbound("slack")).thenReturn(outboundAdapter);

        rule(CostAlertMetric.COST_USD, CostAlertScope.GLOBAL, "", 100.0,
            JSON.toJSON(List.of(Map.of("type", "channel", "channelId", "c1", "recipient", "C123"))));
        when(traceCollection.aggregate(any())).thenReturn(List.of(row(150.0)));

        service.check(LocalDate.of(2026, 8, 12));

        verify(outboundAdapter, never()).sendText(any(), any(), any(), any(), any());
    }

    @Test
    void invalidTargetsJsonDoesNotBreakCheck() {
        rule(CostAlertMetric.COST_USD, CostAlertScope.GLOBAL, "", 100.0, "{not-json");
        when(traceCollection.aggregate(any())).thenReturn(List.of(row(150.0)));

        service.check(LocalDate.of(2026, 8, 12));

        verify(eventCollection, times(2)).insert(any(CostAlertEvent.class));
        verify(notificationService, never()).create(any(), any(), any(), any(), any(), any());
    }

    private void rule(CostAlertMetric metric, CostAlertScope scope, String scopeValue, double threshold, String targets) {
        var rule = new CostAlertRule();
        rule.id = "rule-1";
        rule.name = "Test Rule";
        rule.enabled = Boolean.TRUE;
        rule.metric = metric;
        rule.scope = scope;
        rule.scopeValue = scopeValue;
        rule.threshold = threshold;
        rule.targets = targets;
        when(ruleCollection.find(any(Query.class))).thenReturn(List.of(rule));
    }
}
