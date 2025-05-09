package ai.core.task.parts;

import ai.core.task.Part;
import ai.core.task.PartType;

import java.util.Map;

/**
 * @author stephen
 */
public class DataPart extends Part<DataPart> {
    private final Map<String, String> metadata;

    public DataPart(Map<String, String> metadata) {
        super(PartType.DATA);
        this.metadata = metadata;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }
}
