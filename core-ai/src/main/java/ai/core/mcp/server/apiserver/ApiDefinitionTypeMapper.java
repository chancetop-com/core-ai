package ai.core.mcp.server.apiserver;

import ai.core.mcp.server.apiserver.domain.ApiDefinitionType;

import java.util.AbstractMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class ApiDefinitionTypeMapper {
    private final List<ApiDefinitionType> allTypes;

    public ApiDefinitionTypeMapper(List<ApiDefinitionType> allTypes) {
        this.allTypes = allTypes;
    }

    public Map<String, Object> buildMap(ApiDefinitionType rootType, Map<String, Object> input) {
        var result = new HashMap<String, Object>();
        for (var field : rootType.fields) {
            var fieldName = field.name;
            var fieldType = field.type;

            var nestedType = findTypeByName(fieldType);
            if (nestedType != null && !"enum".equalsIgnoreCase(nestedType.type)) {
                var nestedMap = buildMap(nestedType, input);
                if (!nestedMap.isEmpty()) {
                    result.put(fieldName, nestedMap);
                }
            } else {
                if (input.containsKey(fieldName)) {
                    result.put(fieldName, input.get(fieldName));
                }
            }
        }
        return result;
    }

    public Map<String, AbstractMap.SimpleEntry<Object, Class<?>>> buildParamsMap(ApiDefinitionType rootType, Map<String, AbstractMap.SimpleEntry<Object, Class<?>>> input) {
        var result = new HashMap<String, AbstractMap.SimpleEntry<Object, Class<?>>>();
        for (var field : rootType.fields) {
            var fieldName = field.name;
            var fieldType = field.type;

            var nestedType = findTypeByName(fieldType);
            if (nestedType != null && !"enum".equalsIgnoreCase(nestedType.type)) {
                var nestedMap = buildParamsMap(nestedType, input);
                if (!nestedMap.isEmpty()) {
                    result.put(fieldName, new AbstractMap.SimpleEntry<>(nestedMap, Map.class));
                }
            } else {
                if (input.containsKey(fieldName)) {
                    var entry = input.get(fieldName);
                    result.put(fieldName, new AbstractMap.SimpleEntry<>(entry.getKey(), entry.getValue()));
                }
            }
        }
        return result;
    }


    private ApiDefinitionType findTypeByName(String typeName) {
        return allTypes.stream()
                .filter(type -> type.name.equals(typeName))
                .findFirst()
                .orElse(null);
    }
}
