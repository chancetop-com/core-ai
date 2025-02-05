package ai.core.litellm.image;

import core.framework.api.json.Property;
import core.framework.api.validate.NotBlank;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class CreateImageAJAXRequest {
    @NotNull
    @Property(name = "model")
    public String model = "dall-e-2";

    @NotNull
    @NotBlank
    @Property(name = "prompt")
    public String prompt;

    @NotNull
    @Property(name = "n")
    public Integer n = 1;

    @NotNull
    @Property(name = "size")
    public AzureDallE3Size size = AzureDallE3Size.SIZE1024X1024;

    public enum AzureDallE3Size {
        @Property(name = "1024x1024")
        SIZE1024X1024,
        @Property(name = "1792x1024")
        SIZE1792X1024,
        @Property(name = "1024x1792")
        SIZE1024X1792
    }
}
