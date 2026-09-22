package ai.core.api.server.media;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class ImageCompareRunResponse {
    @Property(name = "jobId")
    public String jobId;

    @Property(name = "fileId")
    public String fileId;

    @Property(name = "fileName")
    public String fileName;

    @Property(name = "contentType")
    public String contentType;

    @Property(name = "model")
    public String model;

    @Property(name = "resolvedModel")
    public String resolvedModel;

    @Property(name = "costUsd")
    public Double costUsd;

    @Property(name = "costSource")
    public String costSource;

    @Property(name = "mediaUnits")
    public Double mediaUnits;

    @Property(name = "mediaUnitType")
    public String mediaUnitType;

    @Property(name = "durationMs")
    public Long durationMs;
}
