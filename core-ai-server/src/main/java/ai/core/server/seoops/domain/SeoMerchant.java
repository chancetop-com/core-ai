package ai.core.server.seoops.domain;

import core.framework.api.validate.NotNull;
import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * @author xander
 */
@Collection(name = "seo_merchants")
public class SeoMerchant {
    @Id
    public String id;

    @NotNull
    @Field(name = "slug")
    public String slug;

    @NotNull
    @Field(name = "display_name")
    public String displayName;

    @NotNull
    @Field(name = "tags")
    public List<String> tags = new ArrayList<>();

    @NotNull
    @Field(name = "operator_user_ids")
    public List<String> operatorUserIds = new ArrayList<>();

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
