package ai.core.mcp.server.apiserver;

import ai.core.api.apidefinition.ApiDefinitionType;
import ai.core.api.jsonschema.JsonSchema;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.ToolCallParameterType;
import ai.core.utils.JsonSchemaUtil;
import core.framework.util.Lists;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author stephen
 */
public class ApiDefinitionTypeSchemaBuilder {
    private final Map<String, ApiDefinitionType> typeMap;

    public ApiDefinitionTypeSchemaBuilder(List<ApiDefinitionType> types) {
        this.typeMap = types.stream().collect(Collectors.toMap(v -> v.name, java.util.function.Function.identity()));
    }

    public JsonSchema buildSchema(String rootTypeName) {
        var rootType = typeMap.get(rootTypeName);
        if (rootType == null) {
            throw new IllegalArgumentException("root type not found: " + rootTypeName);
        }
        return buildSchema(rootType);
    }

    public JsonSchema buildSchema(ApiDefinitionType rootType) {
        var params = buildParameters(rootType);
        return JsonSchemaUtil.toJsonSchema(params);
    }

    public List<ToolCallParameter> buildParameters(ApiDefinitionType rootType) {
        var params = new ArrayList<ToolCallParameter>();
        buildTypeParams(rootType, params);
        return params;
    }

    private void buildTypeParams(ApiDefinitionType requestType, List<ToolCallParameter> params) {
        var primitiveTypes = Arrays.stream(ToolCallParameterType.values()).map(v -> v.name().toUpperCase(Locale.ROOT)).collect(Collectors.toSet());
        for (var field : requestType.fields) {
            if ("list".equalsIgnoreCase(field.type)) {
                params.add(toParamList(field));
                continue;
            }
            if ("map".equalsIgnoreCase(field.type)) {
                params.add(toParamMap(field));
                continue;
            }
            if (typeMap.containsKey(field.type)) {
                var subType = typeMap.get(field.type);
                if ("enum".equalsIgnoreCase(subType.type)) {
                    params.add(toParamEnum(field, subType));
                } else {
                    var param = new ToolCallParameter();
                    param.setName(field.name);
                    param.setDescription(field.description == null ? field.name : field.description);
                    param.setRequired(field.constraints.notNull);
                    param.setClassType(Map.class);
                    param.setItemType(Object.class);
                    var subParams = new ArrayList<ToolCallParameter>();
                    buildTypeParams(subType, subParams);
                    param.setItems(subParams);
                    params.add(param);
                }
            } else if (primitiveTypes.contains(field.type.toUpperCase(Locale.ROOT))) {
                params.add(toParam(field));
            }
        }
    }

    private ToolCallParameter toParamList(ApiDefinitionType.Field field) {
        var param = new ToolCallParameter();
        param.setName(field.name);
        param.setDescription(field.description == null ? field.name : field.description);
        param.setRequired(field.constraints.notNull);
        param.setClassType(List.class);
        var itemType = field.typeParams != null && !field.typeParams.isEmpty() ? field.typeParams.getFirst() : "String";
        if (typeMap.containsKey(itemType)) {
            var subType = typeMap.get(itemType);
            if ("enum".equalsIgnoreCase(subType.type)) {
                param.setItemType(String.class);
                param.setItemEnums(toEnumConstants(subType));
            } else {
                param.setItemType(Object.class);
                List<ToolCallParameter> subParams = Lists.newArrayList();
                buildTypeParams(subType, subParams);
                param.setItems(subParams);
            }
        } else {
            var type = ToolCallParameterType.valueOf(itemType.toUpperCase(Locale.ROOT));
            param.setItemType(type.getType());
        }
        return param;
    }

    private ToolCallParameter toParamMap(ApiDefinitionType.Field field) {
        var param = new ToolCallParameter();
        param.setName(field.name);
        param.setDescription(field.description == null ? field.name : field.description);
        param.setRequired(field.constraints.notNull);
        param.setClassType(Map.class);
        param.setItemType(Object.class);
        return param;
    }

    private ToolCallParameter toParamEnum(ApiDefinitionType.Field field, ApiDefinitionType enumType) {
        var param = new ToolCallParameter();
        param.setName(field.name);
        param.setDescription(field.description == null ? field.name : field.description);
        param.setRequired(field.constraints.notNull);
        param.setClassType(String.class);
        param.setEnums(toEnumConstants(enumType));
        return param;
    }

    private List<String> toEnumConstants(ApiDefinitionType enumType) {
        return enumType.enumConstants.stream().map(v -> v.name).toList();
    }

    private ToolCallParameter toParam(ApiDefinitionType.Field field) {
        var param = new ToolCallParameter();
        param.setName(field.name);
        param.setDescription(field.description == null ? field.name : field.description);
        param.setRequired(field.constraints.notNull);
        var type = ToolCallParameterType.valueOf(field.type.toUpperCase(Locale.ROOT));
        param.setClassType(type.getType());
        param.setFormat(JsonSchemaUtil.buildJsonSchemaFormat(type));
        return param;
    }
}
