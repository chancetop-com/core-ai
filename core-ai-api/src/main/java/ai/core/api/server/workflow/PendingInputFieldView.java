package ai.core.api.server.workflow;

import core.framework.api.json.Property;

/**
 * One field of an input-mode HUMAN_INPUT form: the resume 'input' JSON object must provide required fields
 * with matching types (text/paragraph/select -> string, number -> number, boolean -> boolean).
 *
 * @author Xander
 */
public class PendingInputFieldView {
    @Property(name = "name")
    public String name;

    @Property(name = "type")
    public String type;

    @Property(name = "label")
    public String label;

    @Property(name = "required")
    public Boolean required;
}
