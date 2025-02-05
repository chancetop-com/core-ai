package ai.core.llm.providers.inner;

/**
 * @author stephen
 */
public class Function {

    public static Function of(String name, String arguments) {
        var function = new Function();
        function.name = name;
        function.arguments = arguments;
        return function;
    }

    public String name;
    public String arguments;
}
