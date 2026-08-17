package ai.core.server.seoops.domain;

import core.framework.api.validate.NotNull;
import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author xander
 */
@Collection(name = "seo_locations")
public class SeoLocation {
    @Id
    public String id;

    @NotNull
    @Field(name = "merchant_id")
    public String merchantId;

    @NotNull
    @Field(name = "slug")
    public String slug;

    @NotNull
    @Field(name = "display_name")
    public String displayName;

    @NotNull
    @Field(name = "timezone")
    public String timezone;

    @NotNull
    @Field(name = "external_identities")
    public Map<String, String> externalIdentities = new HashMap<>();

    @NotNull
    @Field(name = "readiness_status")
    public SeoLocationReadiness readinessStatus;

    @NotNull
    @Field(name = "missing_requirements")
    public List<String> missingRequirements = new ArrayList<>();

    @NotNull
    @Field(name = "creation_idempotency_key")
    public String creationIdempotencyKey;

    @NotNull
    @Field(name = "request_fingerprint")
    public String requestFingerprint;

    @NotNull
    @Field(name = "created_by")
    public String createdBy;

    @NotNull
    @Field(name = "created_at")
    public ZonedDateTime createdAt;

    @NotNull
    @Field(name = "updated_at")
    public ZonedDateTime updatedAt;
}
