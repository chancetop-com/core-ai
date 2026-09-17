package ai.core.server.memory.experiment;

import core.framework.mongo.MongoEnumValue;

/**
 * How the selected memories reach the prompt.
 *
 * <p>{@link #FULL} is the original behaviour: the top-K memories of the enabled layers are written out in
 * full. {@link #LAYERED} keeps knowledge verbatim (what a user asked to remember must not be squeezed out by
 * freshly distilled sessions) and lists the other layers as a compact index of ids, which the agent opens with
 * read_memory when it needs the detail.
 *
 * <p>Recorded on every experiment run: the two modes produce different prompts, so runs are only comparable
 * within one mode.
 *
 * @author Xander
 */
public enum InjectionMode {
    @MongoEnumValue("layered")
    LAYERED,
    @MongoEnumValue("full")
    FULL;

    public String mongoValue() {
        return switch (this) {
            case LAYERED -> "layered";
            case FULL -> "full";
        };
    }
}
