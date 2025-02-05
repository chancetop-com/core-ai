package ai.core.example.api.socialmedia;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class CotResponse {
    @Property(name = "text")
    public String text;

    @Property(name = "cot")
    public List<String> cot;
}
