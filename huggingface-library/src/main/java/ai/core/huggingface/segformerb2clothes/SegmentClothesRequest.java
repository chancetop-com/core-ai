package ai.core.huggingface.segformerb2clothes;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class SegmentClothesRequest {
    @Property(name = "base64_image_data")
    public String base64ImageData;
}
