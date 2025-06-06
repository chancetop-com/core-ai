package ai.core.llm;

/**
 * @author stephen
 */
public enum LLMProviderType {
    DEEPSEEK("deepseek"),
    OPENAI("openai"),
    AZURE("azure"),
    AZURE_INFERENCE("azure-inference"),
    LITELLM("litellm");

    private final String name;

    LLMProviderType(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public static LLMProviderType fromName(String name) {
        for (var type : values()) {
            if (type.getName().equalsIgnoreCase(name)) {
                return type;
            }
        }
        throw new IllegalArgumentException("No enum constant " + LLMProviderType.class.getCanonicalName() + "." + name);
    }
}
