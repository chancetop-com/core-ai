package ai.core.task.parts;

import ai.core.task.Part;
import ai.core.task.PartType;

/**
 * @author stephen
 */
public class TextPart extends Part<TextPart> {
    private final String text;

    public TextPart(String text) {
        super(PartType.TEXT);
        this.text = text;
    }

    public String getText() {
        return text;
    }
}
