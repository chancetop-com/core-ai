package ai.core.huggingface.iclight;

import ai.core.huggingface.Path;
import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class RelightingImageRequest {
    @Property(name = "path")
    public Path path;

    @Property(name = "bg_source")
    public String bgSource = "\"Top Light\""; // None, Left Light, Right Light, Top Light, Bottom Light

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

    @Property(name = "n_prompt")
    public String nPrompt = "\"\"";

    @Property(name = "cfg")
    public Integer cfg = 1;

    @Property(name = "gs")
    public Integer gs = 5;

    @Property(name = "rs")
    public Integer rs = 1;

    @Property(name = "init_denoise")
    public Double initDenoise = 0.999;
}
