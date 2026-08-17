package ai.core.server.seoops;

import ai.core.api.server.seoops.SeoOpsApiModels.CreateLocationRequest;
import ai.core.api.server.seoops.SeoOpsApiModels.CreateMerchantRequest;
import ai.core.server.domain.User;
import ai.core.server.seoops.domain.SeoLocation;
import ai.core.server.seoops.domain.SeoLocationReadiness;
import ai.core.server.seoops.domain.SeoMerchant;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ConflictException;
import core.framework.web.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SeoMerchantServiceTest {
    private final MongoCollection<SeoMerchant> merchants = mock();
    private final MongoCollection<SeoLocation> locations = mock();
    private final MongoCollection<User> users = mock();
    private final SeoMerchantService service = new SeoMerchantService();

    @BeforeEach
    void setUp() {
        service.merchantCollection = merchants;
        service.locationCollection = locations;
        service.userCollection = users;
        when(merchants.find(any(org.bson.conversions.Bson.class))).thenReturn(List.of());
        when(locations.find(any(org.bson.conversions.Bson.class))).thenReturn(List.of());
    }

    @Test
    void createsNormalizedMerchantWithActiveOperatorsAndCreator() {
        var operator = new User();
        operator.id = "user-2";
        operator.status = "active";
        when(users.get("user-2")).thenReturn(Optional.of(operator));
        var request = merchantRequest(" Only-Bear ", "Only Bear", "merchant-create-1");
        request.operatorUserIds = List.of("user-2", "user-2");
        request.tags = List.of(" Restaurant ", "restaurant", "Mineola");

        var created = service.createMerchant("user-1", request);

        var inserted = ArgumentCaptor.forClass(SeoMerchant.class);
        verify(merchants).insert(inserted.capture());
        assertSame(inserted.getValue(), created);
        assertEquals("only-bear", created.slug);
        assertEquals(List.of("user-1", "user-2"), created.operatorUserIds);
        assertEquals(List.of("Restaurant", "restaurant", "Mineola"), created.tags);
    }

    @Test
    void rejectsInactiveRequestedOperatorBeforeInsert() {
        var inactive = new User();
        inactive.id = "user-2";
        inactive.status = "disabled";
        when(users.get("user-2")).thenReturn(Optional.of(inactive));
        var request = merchantRequest("only-bear", "Only Bear", "merchant-create-1");
        request.operatorUserIds = List.of("user-2");

        assertThrows(BadRequestException.class, () -> service.createMerchant("user-1", request));
        verify(merchants, never()).insert(any());
    }

    @Test
    void returnsExactIdempotentReplayAndRejectsChangedPayload() {
        var existing = new SeoMerchant();
        existing.id = "merchant-1";
        existing.creationIdempotencyKey = "merchant-create-1";
        var original = merchantRequest("only-bear", "Only Bear", "merchant-create-1");
        existing.requestFingerprint = service.merchantFingerprint("user-1", original);
        when(merchants.find(any(org.bson.conversions.Bson.class))).thenReturn(List.of(existing));

        assertSame(existing, service.createMerchant("user-1", original));
        assertThrows(ConflictException.class, () -> service.createMerchant(
            "user-1", merchantRequest("only-bear", "Changed", "merchant-create-1")));
        verify(merchants, never()).insert(any());
    }

    @Test
    void validatesLocationReadinessSemantics() {
        var merchant = visibleMerchant();
        when(merchants.get("merchant-1")).thenReturn(Optional.of(merchant));
        var readyWithoutIdentity = locationRequest("READY", List.of(), Map.of());
        var blockedWithoutReason = locationRequest("BLOCKED", List.of(), Map.of("place_id", "place-1"));

        assertThrows(BadRequestException.class,
            () -> service.createLocation("user-1", "merchant-1", readyWithoutIdentity));
        assertThrows(BadRequestException.class,
            () -> service.createLocation("user-1", "merchant-1", blockedWithoutReason));

        var valid = locationRequest("BLOCKED", List.of("missing GBP authorization"), Map.of());
        var created = service.createLocation("user-1", "merchant-1", valid);
        assertEquals(SeoLocationReadiness.BLOCKED, created.readinessStatus);
        assertEquals(List.of("missing GBP authorization"), created.missingRequirements);
    }

    @Test
    void hidesMerchantExistenceFromUsersOutsideOperatorSet() {
        var merchant = visibleMerchant();
        merchant.operatorUserIds = List.of("someone-else");
        when(merchants.get("merchant-1")).thenReturn(Optional.of(merchant));

        assertThrows(NotFoundException.class,
            () -> service.requireVisibleMerchant("user-1", "merchant-1"));
    }

    private CreateMerchantRequest merchantRequest(String slug, String name, String key) {
        var request = new CreateMerchantRequest();
        request.slug = slug;
        request.displayName = name;
        request.idempotencyKey = key;
        request.tags = List.of();
        request.operatorUserIds = List.of();
        return request;
    }

    private CreateLocationRequest locationRequest(String readiness, List<String> missing, Map<String, String> identities) {
        var request = new CreateLocationRequest();
        request.slug = "mineola";
        request.displayName = "Mineola";
        request.timezone = "America/New_York";
        request.readinessStatus = readiness;
        request.missingRequirements = missing;
        request.externalIdentities = identities;
        request.idempotencyKey = "location-create-1";
        return request;
    }

    private SeoMerchant visibleMerchant() {
        var merchant = new SeoMerchant();
        merchant.id = "merchant-1";
        merchant.operatorUserIds = List.of("user-1");
        return merchant;
    }
}
