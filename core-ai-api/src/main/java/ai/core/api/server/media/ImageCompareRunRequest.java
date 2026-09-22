package ai.core.api.server.media;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * One model per call: the compare view fans out across the selected models so each result
 * renders as soon as its own generation returns, and no single request outlives one generation.
 *
 * @author stephen
 */
public class ImageCompareRunRequest {
    @NotNull
    @Property(name = "prompt")
    public String prompt;

    @NotNull
    @Property(name = "model")
    public String model;

    @Property(name = "size")
    public String size;

    @Property(name = "quality")
    public String quality;
}
