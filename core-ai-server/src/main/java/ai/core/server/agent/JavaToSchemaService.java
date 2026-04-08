package ai.core.server.agent;

import ai.core.api.server.agent.ConvertJavaToSchemaResponse;
import ai.core.llm.LLMProviders;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.ResponseFormat;
import ai.core.llm.domain.RoleType;
import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import core.framework.inject.Inject;

import java.util.List;

/**
 * @author Xander
 */
public class JavaToSchemaService {
    private static final String SYSTEM_PROMPT = """
            You are a Java-to-JSON-Schema converter. Given Java class definitions, produce a standard JSON Schema object.

            Rules:
            - Output ONLY valid JSON, no markdown, no explanation
            - Map Java types: String→string, int/Integer/long/Long→integer, double/Double/float/Float→number, boolean/Boolean→boolean
            - Map List<T>→{"type":"array","items":{...}}, Map<K,V>→{"type":"object"}
            - Map enum→{"type":"string","enum":["VALUE1","VALUE2",...]}
            - Use field names as property names
            - Extract "description" from @CoreAiParameter, @JsonProperty, javadoc comments, or field-level comments
            - Mark fields with @NotNull or primitive types as required
            - Set "title" from the root class name
            - Set "type":"object" for the root
            - Nested classes should be inlined as nested object schemas
            - If the input contains multiple classes, the first/main class is the root
            """;

    @Inject
    LLMProviders llmProviders;

    public ConvertJavaToSchemaResponse convert(String javaCode) {
        var response = new ConvertJavaToSchemaResponse();
        try {
            var provider = llmProviders.getProvider();
            var messages = List.of(
                Message.of(RoleType.SYSTEM, SYSTEM_PROMPT),
                Message.of(RoleType.USER, javaCode)
            );
            var request = CompletionRequest.of(new CompletionRequest.CompletionRequestOptions(
                messages, null, 0.0, null, null, false,
                ResponseFormat.jsonObject(), null
            ));
            var result = provider.completion(request);
            var output = result.choices.getFirst().message.content;
            response.schema = JsonUtil.fromJson(new TypeReference<>() { }, output);
        } catch (Exception e) {
            response.error = e.getMessage();
        }
        return response;
    }
}
