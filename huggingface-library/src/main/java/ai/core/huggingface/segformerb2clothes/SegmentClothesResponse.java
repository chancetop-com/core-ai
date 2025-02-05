package ai.core.huggingface.segformerb2clothes;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class SegmentClothesResponse {
    @Property(name = "masks")
    public List<Mask> masks;

    public static class Mask {
        @Property(name = "score")
        public Double score;

        @Property(name = "label")
        public String label;

        @Property(name = "mask")
        public String mask;
    }
}
