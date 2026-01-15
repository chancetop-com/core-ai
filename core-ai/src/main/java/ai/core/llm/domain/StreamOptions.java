package ai.core.llm.domain;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class StreamOptions {
    @NotNull
    @Property(name = "include_usage")
    public Boolean includeUsage = true;
}
