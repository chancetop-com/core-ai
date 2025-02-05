package ai.core.tool.function.converter.response;

import ai.core.tool.function.converter.ResponseConverter;
import core.framework.json.JSON;

/**
 * @author stephen
 */
public class DefaultJsonResponseConverter implements ResponseConverter {
    @Override
    public String convert(Object o) {
        return JSON.toJSON(o);
    }
}
