package ai.core.huggingface.flux;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class FluxInferenceRequest {
    @Property(name = "inputs")
    public String inputs;
}
