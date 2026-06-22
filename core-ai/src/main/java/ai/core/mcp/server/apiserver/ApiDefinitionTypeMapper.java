package ai.core.mcp.server.apiserver;

import ai.core.api.apidefinition.ApiDefinitionType;

import java.util.AbstractMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps LLM-supplied tool arguments onto an API definition's request type.
 * <p>
 * The traversal walks the type graph and the argument graph in lockstep: when it descends
 * into a nested object (or a list element) type, it also descends into that field's own value.
 * An earlier version advanced the type pointer but kept the parent argument map, so every
 * nested object field resolved against the wrong scope and was silently dropped from the body.
 *
 * @author stephen
 */
public class ApiDefinitionTypeMapper {
    private static final String LIST_TYPE = "list";
    private static final String ENUM_TYPE = "enum";

    private final List<ApiDefinitionType> allTypes;

    public ApiDefinitionTypeMapper(List<ApiDefinitionType> allTypes) {
        this.allTypes = allTypes;
    }

    public Map<String, Object> buildMap(ApiDefinitionType rootType, Map<String, Object> input) {
        var result = new HashMap<String, Object>();
        for (var field : rootType.fields) {
            if (!input.containsKey(field.name)) continue;
            var value = input.get(field.name);

            if (LIST_TYPE.equalsIgnoreCase(field.type)) {
                result.put(field.name, mapList(field.typeParams, value));
                continue;
            }

            var nestedType = findTypeByName(field.type);
            if (isObjectType(nestedType) && value instanceof Map<?, ?> nested) {
                var nestedMap = buildMap(nestedType, castMap(nested));
                if (!nestedMap.isEmpty()) result.put(field.name, nestedMap);
                continue;
            }

            result.put(field.name, value); // primitive / enum / map / already-shaped value
        }
        return result;
    }

    private Object mapList(List<String> typeParams, Object value) {
        if (!(value instanceof List<?> list)) return value;
        var elementType = typeParams != null && !typeParams.isEmpty() ? findTypeByName(typeParams.getFirst()) : null;
        if (!isObjectType(elementType)) return value; // list of primitives or enums -> pass through
        return list.stream()
                .map(element -> element instanceof Map<?, ?> map ? buildMap(elementType, castMap(map)) : element)
                .toList();
    }

    public Map<String, AbstractMap.SimpleEntry<Object, Class<?>>> buildParamsMap(ApiDefinitionType rootType, Map<String, AbstractMap.SimpleEntry<Object, Class<?>>> input) {
        var result = new HashMap<String, AbstractMap.SimpleEntry<Object, Class<?>>>();
        for (var field : rootType.fields) {
            var entry = input.get(field.name);
            if (entry == null) continue;

            var nestedType = findTypeByName(field.type);
            if (isObjectType(nestedType) && entry.getKey() instanceof Map<?, ?> nested) {
                var nestedMap = buildParamsMap(nestedType, toTypedArgs(castMap(nested)));
                if (!nestedMap.isEmpty()) {
                    result.put(field.name, new AbstractMap.SimpleEntry<>(nestedMap, Map.class));
                }
            } else {
                result.put(field.name, new AbstractMap.SimpleEntry<>(entry.getKey(), entry.getValue()));
            }
        }
        return result;
    }

    private Map<String, AbstractMap.SimpleEntry<Object, Class<?>>> toTypedArgs(Map<String, Object> raw) {
        var typed = new HashMap<String, AbstractMap.SimpleEntry<Object, Class<?>>>();
        for (var entry : raw.entrySet()) {
            if (entry.getValue() == null) continue;
            typed.put(entry.getKey(), new AbstractMap.SimpleEntry<>(entry.getValue(), entry.getValue().getClass()));
        }
        return typed;
    }

    private boolean isObjectType(ApiDefinitionType type) {
        return type != null && !ENUM_TYPE.equalsIgnoreCase(type.type);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private ApiDefinitionType findTypeByName(String typeName) {
        return allTypes.stream()
                .filter(type -> type.name.equals(typeName))
                .findFirst()
                .orElse(null);
    }
}
