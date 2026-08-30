package ai.core.api.server.media;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class MediaJobView {
    @Property(name = "id")
    public String id;

    @Property(name = "userId")
    public String userId;

    @Property(name = "providerId")
    public String providerId;

    @Property(name = "requestedModel")
    public String requestedModel;

    @Property(name = "resolvedModel")
    public String resolvedModel;

    @Property(name = "mediaType")
    public String mediaType;

    @Property(name = "state")
    public String state;

    @Property(name = "requestedSeconds")
    public Integer requestedSeconds;

    @Property(name = "mediaUnits")
    public Double mediaUnits;

    @Property(name = "mediaUnitType")
    public String mediaUnitType;

    @Property(name = "creditsConsumed")
    public Double creditsConsumed;

    @Property(name = "costUsd")
    public Double costUsd;

    @Property(name = "costSource")
    public String costSource;

    @Property(name = "pricingModelId")
    public String pricingModelId;

    @Property(name = "progress")
    public Integer progress;

    @Property(name = "error")
    public String error;

    @Property(name = "fileId")
    public String fileId;

    @Property(name = "fileName")
    public String fileName;

    @Property(name = "contentType")
    public String contentType;

    @Property(name = "createdAt")
    public ZonedDateTime createdAt;

    @Property(name = "completedAt")
    public ZonedDateTime completedAt;
}
