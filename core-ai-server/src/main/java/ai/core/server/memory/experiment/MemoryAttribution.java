package ai.core.server.memory.experiment;

import core.framework.mongo.Field;

/**
 * Attribution for a single injected memory — records whether and how
 * the memory contributed to the outcome.
 *
 * Embedded inside {@link AgentMemoryExperimentRun}, not a standalone collection.
 *
 * @author stephen
 */
public class MemoryAttribution {
    @Field(name = "memory_id")
    public String memoryId;

    @Field(name = "memory_layer")
    public MemoryLayer memoryLayer;

    @Field(name = "memory_type")
    public String memoryType;

    @Field(name = "rank")
    public Integer rank;

    @Field(name = "score")
    public Double score;

    @Field(name = "selected")
    public Boolean selected;

    /** Whether the model actually referenced/utilized this memory (LLM judge or heuristic). */
    @Field(name = "used")
    public Boolean used;

    /** Whether this memory contributed positively to the outcome. */
    @Field(name = "helpful")
    public Boolean helpful;
}
