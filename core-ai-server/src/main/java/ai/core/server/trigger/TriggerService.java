package ai.core.server.trigger;

import ai.core.api.server.trigger.CreateTriggerRequest;
import ai.core.api.server.trigger.ListTriggersResponse;
import ai.core.api.server.trigger.TriggerView;
import ai.core.api.server.trigger.UpdateTriggerRequest;
import ai.core.server.trigger.domain.Trigger;
import ai.core.server.trigger.domain.TriggerType;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.UUID;

/**
 * @author stephen
 */
public class TriggerService {
    private static final Logger LOGGER = LoggerFactory.getLogger(TriggerService.class);

    @Inject
    MongoCollection<Trigger> triggerCollection;

    public String publicUrl = "http://localhost:8080";

    public TriggerView create(CreateTriggerRequest request, String userId) {
        var entity = new Trigger();
        entity.id = UUID.randomUUID().toString();
        entity.userId = userId;
        entity.name = request.name;
        entity.description = request.description;
        entity.type = request.type != null ? TriggerType.valueOf(request.type.toUpperCase()) : TriggerType.WEBHOOK;
        entity.enabled = true;
        entity.actionType = request.actionType;
        entity.actionConfig = request.actionConfig;
        entity.createdAt = ZonedDateTime.now();
        entity.updatedAt = entity.createdAt;

        var config = request.config != null ? new HashMap<>(request.config) : new HashMap<String, String>();
        if (entity.type == TriggerType.WEBHOOK) {
            config.putIfAbsent("verifier_type", "bearer");
            String verifierType = config.get("verifier_type");
            if ("bearer".equals(verifierType)) {
                config.remove("slack_signing_secret");
                config.putIfAbsent("secret", "whk_" + UUID.randomUUID().toString().replace("-", ""));
                if (config.get("secret") == null || config.get("secret").isBlank()) {
                    config.remove("secret");
                }
            } else if ("slack".equals(verifierType)) {
                config.remove("secret");
            } else {
                // "none" or unknown — clear all auth keys
                config.remove("secret");
                config.remove("slack_signing_secret");
            }
        }
        entity.config = config;

        triggerCollection.insert(entity);
        LOGGER.info("trigger created, id={}, name={}, type={}", entity.id, entity.name, entity.type);
        return toView(entity);
    }

    public ListTriggersResponse list(String type) {
        var filter = type != null && !type.isBlank()
                ? Filters.eq("type", type.toUpperCase())
                : Filters.empty();
        var entities = triggerCollection.find(filter);
        var response = new ListTriggersResponse();
        response.triggers = entities.stream().map(this::toView).toList();
        response.total = (long) response.triggers.size();
        return response;
    }

    public TriggerView get(String id) {
        var entity = triggerCollection.get(id)
                .orElseThrow(() -> new RuntimeException("trigger not found, id=" + id));
        return toView(entity);
    }

    public Trigger getEntity(String id) {
        return triggerCollection.get(id)
                .orElseThrow(() -> new RuntimeException("trigger not found, id=" + id));
    }

    public TriggerView update(String id, UpdateTriggerRequest request, String userId) {
        var entity = triggerCollection.get(id)
                .orElseThrow(() -> new RuntimeException("trigger not found, id=" + id));

        if (request.name != null) entity.name = request.name;
        if (request.description != null) entity.description = request.description;
        if (request.enabled != null) entity.enabled = request.enabled;
        if (request.actionType != null) entity.actionType = request.actionType;
        if (request.actionConfig != null) entity.actionConfig = request.actionConfig;
        if (request.config != null) {
            var merged = new HashMap<>(entity.config != null ? entity.config : new HashMap<>());
            merged.putAll(request.config);
            // Clean up stale keys based on verifier_type
            String verifierType = merged.getOrDefault("verifier_type", "bearer");
            if ("none".equals(verifierType)) {
                merged.remove("secret");
                merged.remove("slack_signing_secret");
            } else if ("bearer".equals(verifierType)) {
                merged.remove("slack_signing_secret");
            } else if ("slack".equals(verifierType)) {
                merged.remove("secret");
            }
            entity.config = merged;
        }
        entity.updatedAt = ZonedDateTime.now();

        triggerCollection.replace(entity);
        return toView(entity);
    }

    public void delete(String id) {
        triggerCollection.delete(id);
        LOGGER.info("trigger deleted, id={}", id);
    }

    public TriggerView enable(String id) {
        var entity = getEntity(id);
        entity.enabled = true;
        entity.updatedAt = ZonedDateTime.now();
        triggerCollection.replace(entity);
        return toView(entity);
    }

    public TriggerView disable(String id) {
        var entity = getEntity(id);
        entity.enabled = false;
        entity.updatedAt = ZonedDateTime.now();
        triggerCollection.replace(entity);
        return toView(entity);
    }

    public TriggerView rotateSecret(String id) {
        var entity = getEntity(id);
        if (entity.type != TriggerType.WEBHOOK) {
            throw new RuntimeException("secret rotation is only supported for WEBHOOK triggers");
        }
        var config = new HashMap<>(entity.config != null ? entity.config : new HashMap<>());
        config.put("secret", "whk_" + UUID.randomUUID().toString().replace("-", ""));
        entity.config = config;
        entity.updatedAt = ZonedDateTime.now();
        triggerCollection.replace(entity);
        return toView(entity);
    }

    TriggerView toView(Trigger entity) {
        var view = new TriggerView();
        view.id = entity.id;
        view.name = entity.name;
        view.description = entity.description;
        view.type = entity.type.name();
        view.enabled = entity.enabled;
        view.config = entity.config;
        view.actionType = entity.actionType;
        view.actionConfig = entity.actionConfig;
        view.lastTriggeredAt = entity.lastTriggeredAt;
        view.createdAt = entity.createdAt;
        view.updatedAt = entity.updatedAt;

        if (entity.type == TriggerType.WEBHOOK) {
            var baseUrl = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
            view.webhookUrl = baseUrl + "/api/webhook-triggers/" + entity.id;
        }

        return view;
    }
}
