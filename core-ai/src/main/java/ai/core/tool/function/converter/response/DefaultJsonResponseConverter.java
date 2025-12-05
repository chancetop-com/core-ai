package ai.core.tool.function.converter.response;

import ai.core.tool.function.converter.ResponseConverter;
import ai.core.utils.JsonUtil;

/**
 * @author stephen
 */
public class DefaultJsonResponseConverter implements ResponseConverter {
    @Override
    public String convert(Object o) {
        if (o == null) return null;
        if (o instanceof String) return (String) o;
        return JsonUtil.toJson(o);
    }
}
