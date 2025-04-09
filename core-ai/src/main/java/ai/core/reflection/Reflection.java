package ai.core.reflection;

/**
 * @author stephen
 */
public class Reflection {
    public static final String DEFAULT_REFLECTION_CONTINUE_TEMPLATE = """
        Carefully read through the entire conversation history, analyze and determine whether we have completed the original requirements.
        If we need to continue reflecting, consider more analysis approaches and solutions.
        If not, return TERMINATE with the answer of the requirement and make the word 'TERMINATE' at the beginning of the message.
        """;
}
