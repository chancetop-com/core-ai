package ai.core.server.run;

import ai.core.api.apidefinition.ApiDefinitionType;
import ai.core.llm.domain.ResponseFormat;
import ai.core.mcp.server.apiserver.ApiDefinitionTypeSchemaBuilder;

import java.util.List;

/**
 * @author stephen
 */
public class ResponseSchemaConverter {
    public static ResponseFormat toResponseFormat(List<ApiDefinitionType> types) {
        var rootType = types.getFirst();
        var schema = new ApiDefinitionTypeSchemaBuilder(types).buildSchema(rootType);

        var format = new ResponseFormat();
        var schemaDef = new ResponseFormat.JsonSchemaDefinition();
        schemaDef.name = rootType.name;
        schemaDef.strict = false;
        schemaDef.schema = schema;
        format.jsonSchema = schemaDef;
        return format;
    }
}
