package ai.core.huggingface.flux;

import ai.core.huggingface.Path;
import core.framework.api.json.Property;
import core.framework.json.JSON;

/**
 * @author stephen
 */
public class FillingImageRequest {
    @Property(name = "bg")
    public String bg;

    @Property(name = "mask")
    public String mask;

    @Property(name = "background")
    public Background background;

    @Property(name = "prompt")
    public String prompt;

    @Property(name = "seed")
    public Integer seed = 12345;

    @Property(name = "randomize_seed")
    public Boolean randomizeSeed = Boolean.TRUE;

    @Property(name = "width")
    public Integer imageWidth = 512;

    @Property(name = "height")
    public Integer imageHeight = 640;

    @Property(name = "guidance_scale")
    public Integer guidanceScale = 50;

    @Property(name = "num_inference_steps")
    public Integer numInferenceSteps = 28;

    public static class Background {
        @Property(name = "background")
        public Path background;

        @Override
        public String toString() {
            return JSON.toJSON(this);
        }
    }
}
