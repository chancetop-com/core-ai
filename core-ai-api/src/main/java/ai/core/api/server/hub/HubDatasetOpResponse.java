package ai.core.api.server.hub;

import core.framework.api.json.Property;

/**
 * The op result: {@code payload} is the exact JSON text the matching builtin tool produced, so a
 * client parses the same shape in a sandbox and outside one. Only the list endpoint answers with a
 * typed view — everything else keeps the single text payload.
 *
 * @author stephen
 */
public class HubDatasetOpResponse {
    // datasets.list | state.get | state.set | state.patch | records.query | records.insert |
    // records.update | records.delete
    @Property(name = "op")
    public String op;

    @Property(name = "payload")
    public String payload;
}
