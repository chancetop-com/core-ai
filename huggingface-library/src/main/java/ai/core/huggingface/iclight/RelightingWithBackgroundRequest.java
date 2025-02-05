package ai.core.huggingface.iclight;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class RelightingWithBackgroundRequest {
    @Property(name = "fg")
    public String fg;

    @Property(name = "bg")
    public String bg;

    @Property(name = "prompt")
    public String prompt;

    @Property(name = "image_width")
    public Integer imageWidth = 512;

    @Property(name = "image_height")
    public Integer imageHeight = 640;

    @Property(name = "num_samples")
    public Integer numSamples = 1;

    @Property(name = "seed")
    public Integer seed = 12345;

    @Property(name = "step")
    public Integer steps = 25;

    @Property(name = "a_prompt")
    public String aPrompt = "\"best quality\"";

    @Property(name = "n_prompt")
    public String nPrompt = "\"lowres, bad anatomy, bad hands, cropped, worst quality\"";

    @Property(name = "cfg")
    public Integer cfg = 7;

    @Property(name = "highresScale")
    public Double highresScale = 1.5;

    @Property(name = "highres_denoise")
    public Double highresDenoise = 0.5;

    @Property(name = "bg_source")
    public String bgSource = "\"Use Background Image\"";
}
