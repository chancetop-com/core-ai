package ai.core.server.seoops;

import ai.core.api.server.seoops.SeoOpsApiModels.CreateLocationRequest;
import ai.core.api.server.seoops.SeoOpsApiModels.CreateMerchantRequest;
import ai.core.server.domain.User;
import ai.core.server.seoops.domain.SeoLocation;
import ai.core.server.seoops.domain.SeoLocationReadiness;
import ai.core.server.seoops.domain.SeoMerchant;
import ai.core.server.web.auth.RequestAuthenticator;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ConflictException;
import core.framework.web.exception.NotFoundException;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * @author xander
 */
public class SeoMerchantService {
    @Inject
    MongoCollection<SeoMerchant> merchantCollection;

    @Inject
    MongoCollection<SeoLocation> locationCollection;

    @Inject
    MongoCollection<User> userCollection;

    public SeoMerchant createMerchant(String actorUserId, CreateMerchantRequest request) {
        requireActor(actorUserId);
        if (request == null) throw new BadRequestException("request is required");
        var slug = normalizeSlug(request.slug);
        var displayName = requireText(request.displayName, "display_name");
        var idempotencyKey = requireText(request.idempotencyKey, "idempotency_key");
        var tags = cleanList(request.tags);
        var requestedOperators = cleanList(request.operatorUserIds);
        var fingerprint = merchantFingerprint(actorUserId, request);
        var replay = findMerchantByIdempotencyKey(idempotencyKey);
        if (replay != null) return requireSameFingerprint(replay, fingerprint);
        if (findMerchantBySlug(slug) != null) throw new ConflictException("merchant slug already exists");

        var operators = new LinkedHashSet<String>();
        operators.add(actorUserId);
        for (var operatorId : requestedOperators) {
            var user = userCollection.get(operatorId).orElse(null);
            if (user == null || !"active".equals(user.status)) {
                throw new BadRequestException("operator_user_ids contains an inactive or unknown user");
            }
            operators.add(operatorId);
        }

        var now = ZonedDateTime.now();
        var merchant = new SeoMerchant();
        merchant.id = UUID.randomUUID().toString();
        merchant.slug = slug;
        merchant.displayName = displayName;
        merchant.tags = tags;
        merchant.operatorUserIds = new ArrayList<>(operators);
        merchant.creationIdempotencyKey = idempotencyKey;
        merchant.requestFingerprint = fingerprint;
        merchant.createdBy = actorUserId;
        merchant.createdAt = now;
        merchant.updatedAt = now;
        try {
            merchantCollection.insert(merchant);
            return merchant;
        } catch (RuntimeException e) {
            var winner = findMerchantByIdempotencyKey(idempotencyKey);
            if (winner != null) {
                if (Objects.equals(winner.requestFingerprint, fingerprint)) return winner;
                throw new ConflictException("idempotency key was already used with different content",
                    "IDEMPOTENCY_KEY_REUSED", e);
            }
            if (findMerchantBySlug(slug) != null) {
                throw new ConflictException("merchant slug already exists", "MERCHANT_SLUG_EXISTS", e);
            }
            throw e;
        }
    }

    public SeoLocation createLocation(String actorUserId, String merchantId, CreateLocationRequest request) {
        var merchant = requireVisibleMerchant(actorUserId, merchantId);
        if (request == null) throw new BadRequestException("request is required");
        var slug = normalizeSlug(request.slug);
        var displayName = requireText(request.displayName, "display_name");
        var timezone = requireText(request.timezone, "timezone");
        validateTimezone(timezone);
        var idempotencyKey = requireText(request.idempotencyKey, "idempotency_key");
        var readiness = parseReadiness(request.readinessStatus);
        var missingRequirements = cleanList(request.missingRequirements);
        var externalIdentities = cleanMap(request.externalIdentities);
        validateReadiness(readiness, missingRequirements, externalIdentities);
        var fingerprint = locationFingerprint(merchant.id, request);

        var replay = findLocationByIdempotencyKey(merchant.id, idempotencyKey);
        if (replay != null) return requireSameFingerprint(replay, fingerprint);
        if (findLocationBySlug(merchant.id, slug) != null) throw new ConflictException("location slug already exists");

        var now = ZonedDateTime.now();
        var location = new SeoLocation();
        location.id = UUID.randomUUID().toString();
        location.merchantId = merchant.id;
        location.slug = slug;
        location.displayName = displayName;
        location.timezone = timezone;
        location.externalIdentities = externalIdentities;
        location.readinessStatus = readiness;
        location.missingRequirements = missingRequirements;
        location.creationIdempotencyKey = idempotencyKey;
        location.requestFingerprint = fingerprint;
        location.createdBy = actorUserId;
        location.createdAt = now;
        location.updatedAt = now;
        try {
            locationCollection.insert(location);
            return location;
        } catch (RuntimeException e) {
            var winner = findLocationByIdempotencyKey(merchant.id, idempotencyKey);
            if (winner != null) {
                if (Objects.equals(winner.requestFingerprint, fingerprint)) return winner;
                throw new ConflictException("idempotency key was already used with different content",
                    "IDEMPOTENCY_KEY_REUSED", e);
            }
            if (findLocationBySlug(merchant.id, slug) != null) {
                throw new ConflictException("location slug already exists", "LOCATION_SLUG_EXISTS", e);
            }
            throw e;
        }
    }

    public SeoMerchant requireVisibleMerchant(String actorUserId, String merchantId) {
        requireActor(actorUserId);
        if (merchantId == null || merchantId.isBlank()) throw new NotFoundException("merchant not found");
        var merchant = merchantCollection.get(merchantId).orElse(null);
        if (merchant == null || merchant.operatorUserIds == null || !merchant.operatorUserIds.contains(actorUserId)) {
            throw new NotFoundException("merchant not found");
        }
        return merchant;
    }

    public SeoLocation requireVisibleLocation(String actorUserId, String merchantId, String locationId) {
        requireVisibleMerchant(actorUserId, merchantId);
        var location = locationId == null ? null : locationCollection.get(locationId).orElse(null);
        if (location == null || !merchantId.equals(location.merchantId)) {
            throw new NotFoundException("location not found");
        }
        return location;
    }

    public List<String> visibleMerchantIds(String actorUserId) {
        requireActor(actorUserId);
        return merchantCollection.find(Filters.eq("operator_user_ids", actorUserId))
            .stream()
            .map(merchant -> merchant.id)
            .toList();
    }

    String merchantFingerprint(String actorUserId, CreateMerchantRequest request) {
        return fingerprint(actorUserId, normalizeSlug(request.slug), requireText(request.displayName, "display_name"),
            String.join("\u001f", cleanList(request.tags)), String.join("\u001f", cleanList(request.operatorUserIds)));
    }

    private String locationFingerprint(String merchantId, CreateLocationRequest request) {
        var externalIdentities = cleanMap(request.externalIdentities);
        var identities = externalIdentities.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> entry.getKey() + "=" + entry.getValue())
            .toList();
        return fingerprint(merchantId, normalizeSlug(request.slug), requireText(request.displayName, "display_name"),
            requireText(request.timezone, "timezone"), parseReadiness(request.readinessStatus).name(),
            String.join("\u001f", cleanList(request.missingRequirements)), String.join("\u001f", identities));
    }

    private String fingerprint(String... values) {
        var encoded = new StringBuilder();
        for (var value : values) {
            var safe = value == null ? "" : value;
            encoded.append(safe.length()).append(':').append(safe).append('|');
        }
        return "sha256:" + RequestAuthenticator.sha256(encoded.toString());
    }

    private SeoMerchant findMerchantByIdempotencyKey(String key) {
        var matches = merchantCollection.find(Filters.eq("creation_idempotency_key", key));
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private SeoMerchant findMerchantBySlug(String slug) {
        var matches = merchantCollection.find(Filters.eq("slug", slug));
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private SeoLocation findLocationByIdempotencyKey(String merchantId, String key) {
        var matches = locationCollection.find(Filters.and(
            Filters.eq("merchant_id", merchantId), Filters.eq("creation_idempotency_key", key)));
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private SeoLocation findLocationBySlug(String merchantId, String slug) {
        var matches = locationCollection.find(Filters.and(
            Filters.eq("merchant_id", merchantId), Filters.eq("slug", slug)));
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private SeoMerchant requireSameFingerprint(SeoMerchant merchant, String fingerprint) {
        if (!Objects.equals(merchant.requestFingerprint, fingerprint)) {
            throw new ConflictException("idempotency key was already used with different content");
        }
        return merchant;
    }

    private SeoLocation requireSameFingerprint(SeoLocation location, String fingerprint) {
        if (!Objects.equals(location.requestFingerprint, fingerprint)) {
            throw new ConflictException("idempotency key was already used with different content");
        }
        return location;
    }

    private SeoLocationReadiness parseReadiness(String value) {
        try {
            return SeoLocationReadiness.valueOf(requireText(value, "readiness_status").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("readiness_status is invalid", "INVALID_LOCATION_READINESS", e);
        }
    }

    private void validateTimezone(String timezone) {
        try {
            ZoneId.of(timezone);
        } catch (RuntimeException e) {
            throw new BadRequestException("timezone is invalid", "INVALID_TIMEZONE", e);
        }
    }

    private void validateReadiness(SeoLocationReadiness readiness, List<String> missingRequirements,
                                   Map<String, String> externalIdentities) {
        if (readiness == SeoLocationReadiness.READY) {
            if (!missingRequirements.isEmpty() || externalIdentities.isEmpty()) {
                throw new BadRequestException("READY requires an external identity and no missing requirements");
            }
        } else if (missingRequirements.isEmpty()) {
            throw new BadRequestException(readiness + " requires explicit missing requirements");
        }
    }

    private String normalizeSlug(String value) {
        var slug = requireText(value, "slug").toLowerCase(Locale.ROOT);
        if (!slug.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
            throw new BadRequestException("slug must contain lowercase letters, numbers, and single hyphens");
        }
        return slug;
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new BadRequestException(field + " is required");
        return value.trim();
    }

    private List<String> cleanList(List<String> values) {
        if (values == null) return List.of();
        var clean = new LinkedHashSet<String>();
        for (var value : values) {
            if (value != null && !value.isBlank()) clean.add(value.trim());
        }
        return new ArrayList<>(clean);
    }

    private Map<String, String> cleanMap(Map<String, String> values) {
        if (values == null) return Map.of();
        var clean = new LinkedHashMap<String, String>();
        for (var entry : values.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()
                || entry.getValue() == null || entry.getValue().isBlank()) continue;
            clean.put(entry.getKey().trim(), entry.getValue().trim());
        }
        return clean;
    }

    private void requireActor(String actorUserId) {
        if (actorUserId == null || actorUserId.isBlank()) throw new NotFoundException("merchant not found");
    }
}
