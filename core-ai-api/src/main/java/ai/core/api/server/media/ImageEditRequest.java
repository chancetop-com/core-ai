package ai.core.api.server.media;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class ImageEditRequest {
    @NotNull
    @Property(name = "sourceFileId")
    public String sourceFileId;

    @NotNull
    @Property(name = "prompt")
    public String prompt;

    @NotNull
    @Property(name = "mask")
    public String mask;

    @Property(name = "model")
    public String model;

    @Property(name = "size")
    public String size;

    @Property(name = "sessionId")
    public String sessionId;

    /** "annotation": the caller accepts an approximate edit for a model that cannot take a mask. */
    @Property(name = "maskFallback")
    public String maskFallback;
}
