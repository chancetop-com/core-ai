package ai.core.server.costalert;

import ai.core.api.server.costalert.CostAlertAdminWebService;
import ai.core.api.server.costalert.request.ListCostAlertEventsRequest;
import ai.core.api.server.costalert.request.SaveCostAlertRuleRequest;
import ai.core.api.server.costalert.response.CostAlertEventView;
import ai.core.api.server.costalert.response.CostAlertRuleView;
import ai.core.api.server.costalert.response.ListCostAlertEventsResponse;
import ai.core.api.server.costalert.response.ListCostAlertRulesResponse;
import ai.core.server.channel.ChannelConfigStore;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.json.JSON;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.NotFoundException;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Admin CRUD for cost alert rules and fired alert events.
 *
 * @author stephen
 */
public class CostAlertAdminWebServiceImpl implements CostAlertAdminWebService {
    private static final Logger LOGGER = LoggerFactory.getLogger(CostAlertAdminWebServiceImpl.class);
    private static final ZoneOffset UTC = ZoneOffset.UTC;
    private static final int MAX_EVENTS = 200;

    @Inject
    MongoCollection<CostAlertRule> ruleCollection;
    @Inject
    MongoCollection<CostAlertEvent> eventCollection;
    @Inject
    ChannelConfigStore channelConfigStore;

    @Override
    public ListCostAlertRulesResponse list() {
        var response = new ListCostAlertRulesResponse();
        response.rules = new ArrayList<>(16);
        for (var rule : ruleCollection.find(new Query())) {
            response.rules.add(toRuleView(rule));
        }
        return response;
    }

    @Override
    public CostAlertRuleView create(SaveCostAlertRuleRequest request) {
        var rule = new CostAlertRule();
        rule.id = new ObjectId().toHexString();
        rule.createdAt = ZonedDateTime.now(UTC);
        rule.updatedAt = rule.createdAt;
        applyRequest(rule, request);
        ruleCollection.insert(rule);
        LOGGER.info("cost alert rule created, id={}, name={}, metric={}, scope={}", rule.id, rule.name, rule.metric, rule.scope);
        return toRuleView(rule);
    }

    @Override
    public CostAlertRuleView get(String id) {
        var rule = ruleCollection.get(id).orElseThrow(() -> new NotFoundException("cost alert rule not found: " + id));
        return toRuleView(rule);
    }

    @Override
    public CostAlertRuleView update(String id, SaveCostAlertRuleRequest request) {
        var rule = ruleCollection.get(id).orElseThrow(() -> new NotFoundException("cost alert rule not found: " + id));
        applyRequest(rule, request);
        rule.updatedAt = ZonedDateTime.now(UTC);
        ruleCollection.replace(rule);
        LOGGER.info("cost alert rule updated, id={}", id);
        return toRuleView(rule);
    }

    @Override
    public void delete(String id) {
        if (ruleCollection.get(id).isEmpty()) throw new NotFoundException("cost alert rule not found: " + id);
        ruleCollection.delete(id);
        LOGGER.info("cost alert rule deleted, id={}", id);
    }

    @Override
    public ListCostAlertEventsResponse events(ListCostAlertEventsRequest request) {
        var filters = new ArrayList<org.bson.conversions.Bson>();
        if (request.ruleId != null && !request.ruleId.isBlank()) filters.add(Filters.eq("rule_id", request.ruleId));
        if (request.dateFrom != null && !request.dateFrom.isBlank()) {
            filters.add(Filters.gte("date", LocalDate.parse(request.dateFrom).atStartOfDay(UTC)));
        }
        if (request.dateTo != null && !request.dateTo.isBlank()) {
            filters.add(Filters.lt("date", LocalDate.parse(request.dateTo).plusDays(1).atStartOfDay(UTC)));
        }
        int limit = request.limit == null ? 100 : Math.clamp(request.limit, 1, MAX_EVENTS);

        var query = new Query();
        if (!filters.isEmpty()) query.filter = Filters.and(filters);
        query.sort = Sorts.descending("created_at");
        query.limit = limit;

        var response = new ListCostAlertEventsResponse();
        response.events = new ArrayList<>(16);
        for (var event : eventCollection.find(query)) {
            response.events.add(toEventView(event));
        }
        return response;
    }

    private void applyRequest(CostAlertRule rule, SaveCostAlertRuleRequest request) {
        rule.name = parseName(request);
        rule.metric = parseMetric(request);
        rule.scope = parseScope(request);
        rule.scopeValue = parseScopeValue(request, rule.scope);
        rule.threshold = parseThreshold(request);
        rule.targets = parseTargets(request);
        if (request.enabled != null) rule.enabled = request.enabled;
        validateTargets(rule.targets);
    }

    private String parseName(SaveCostAlertRuleRequest request) {
        if (request.name == null || request.name.isBlank()) throw new BadRequestException("name is required");
        return request.name.trim();
    }

    private CostAlertMetric parseMetric(SaveCostAlertRuleRequest request) {
        if (request.metric == null || request.metric.isBlank()) throw new BadRequestException("metric is required");
        return switch (request.metric.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "cost_usd" -> CostAlertMetric.COST_USD;
            case "total_tokens" -> CostAlertMetric.TOTAL_TOKENS;
            case "call_count" -> CostAlertMetric.CALL_COUNT;
            default -> throw new BadRequestException("invalid metric: " + request.metric + ", expected cost_usd|total_tokens|call_count");
        };
    }

    private CostAlertScope parseScope(SaveCostAlertRuleRequest request) {
        if (request.scope == null || request.scope.isBlank()) throw new BadRequestException("scope is required");
        return switch (request.scope.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "global" -> CostAlertScope.GLOBAL;
            case "user" -> CostAlertScope.USER;
            case "agent" -> CostAlertScope.AGENT;
            default -> throw new BadRequestException("invalid scope: " + request.scope + ", expected global|user|agent");
        };
    }

    private String parseScopeValue(SaveCostAlertRuleRequest request, CostAlertScope scope) {
        var scopeValue = request.scopeValue == null ? "" : request.scopeValue.trim();
        if (scope != CostAlertScope.GLOBAL && scopeValue.isBlank()) {
            throw new BadRequestException("scopeValue is required for scope " + scope);
        }
        return scope == CostAlertScope.GLOBAL ? "" : scopeValue;
    }

    private Double parseThreshold(SaveCostAlertRuleRequest request) {
        if (request.threshold == null) throw new BadRequestException("threshold is required");
        if (request.threshold <= 0) throw new BadRequestException("threshold must be positive");
        return request.threshold;
    }

    private String parseTargets(SaveCostAlertRuleRequest request) {
        if (request.targets == null) throw new BadRequestException("targets is required");
        return request.targets;
    }

    @SuppressWarnings("unchecked")
    private void validateTargets(String targetsJson) {
        List<Map<String, Object>> targets;
        try {
            targets = (List<Map<String, Object>>) JSON.fromJSON(List.class, targetsJson);
        } catch (Exception e) {
            throw new BadRequestException("targets must be a JSON array", "BAD_REQUEST", e);
        }
        if (targets.isEmpty()) throw new BadRequestException("at least one target is required");
        for (var target : targets) {
            var type = (String) target.get("type");
            if ("notification".equals(type)) {
                var userId = (String) target.get("userId");
                if (userId == null || userId.isBlank()) throw new BadRequestException("notification target requires userId");
            } else if ("channel".equals(type)) {
                var channelId = (String) target.get("channelId");
                var recipient = (String) target.get("recipient");
                if (channelId == null || channelId.isBlank()) throw new BadRequestException("channel target requires channelId");
                if (recipient == null || recipient.isBlank()) throw new BadRequestException("channel target requires recipient");
                var channel = channelConfigStore.load(channelId);
                if (channel == null) throw new BadRequestException("channel not found: " + channelId);
                if (!"slack".equals(channel.channelType)) {
                    throw new BadRequestException("only slack channels are supported for cost alerts, channelType=" + channel.channelType);
                }
            } else {
                throw new BadRequestException("invalid target type: " + type + ", expected notification|channel");
            }
        }
    }

    private CostAlertRuleView toRuleView(CostAlertRule rule) {
        var view = new CostAlertRuleView();
        view.id = rule.id;
        view.name = rule.name;
        view.enabled = rule.enabled;
        view.metric = rule.metric.name();
        view.scope = rule.scope.name();
        view.scopeValue = rule.scopeValue;
        view.threshold = rule.threshold;
        view.targets = rule.targets;
        view.createdAt = rule.createdAt;
        view.updatedAt = rule.updatedAt;
        return view;
    }

    private CostAlertEventView toEventView(CostAlertEvent event) {
        var view = new CostAlertEventView();
        view.id = event.id;
        view.ruleId = event.ruleId;
        view.ruleName = event.ruleName;
        view.date = event.date;
        view.scope = event.scope;
        view.scopeValue = event.scopeValue;
        view.metric = event.metric;
        view.threshold = event.threshold;
        view.actualValue = event.actualValue;
        view.detail = event.detail;
        view.status = event.status;
        view.createdAt = event.createdAt;
        return view;
    }
}
