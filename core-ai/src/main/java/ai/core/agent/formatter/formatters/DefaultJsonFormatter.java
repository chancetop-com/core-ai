package ai.core.agent.formatter.formatters;

import ai.core.agent.formatter.Formatter;

/**
 * @author stephen
 */
public class DefaultJsonFormatter implements Formatter {
    private final boolean dirty;

    public DefaultJsonFormatter() {
        dirty = false;
    }

    public DefaultJsonFormatter(boolean dirty) {
        this.dirty = dirty;
    }

    @Override
    public String formatter(String rst) {
        var completion = rst;
        // Remove leading and trailing whitespace
        completion = completion.trim();

        var splits = completion.split("```");
        // Check for and remove triple backticks and "json" identifier
        if (completion.startsWith("```") && completion.endsWith("```")) {
            completion = removeTripleBackticksAndJsonIdentified(completion);
        } else if (dirty && splits.length == 2 && completion.endsWith("```")) {
            completion = completion.substring(completion.indexOf("```"));
            completion = removeTripleBackticksAndJsonIdentified(completion);
        } else if (dirty && splits.length == 3 && completion.startsWith("```")) {
            completion = completion.substring(0, completion.lastIndexOf("```") + 3);
            completion = removeTripleBackticksAndJsonIdentified(completion);
        }

        completion = completion.trim();
        return completion;
    }

    private String removeTripleBackticksAndJsonIdentified(String rst) {
        var completion = rst;
        // Remove the first line if it contains "```json"
        var lines = completion.split("\n", 2);
        if ("```json".equalsIgnoreCase(lines[0].trim())) {
            completion = lines.length > 1 ? lines[1] : "";
        } else {
            completion = completion.substring(3); // Remove leading ```
        }

        // Remove trailing ```
        completion = completion.substring(0, completion.length() - 3);

        // Trim again to remove any potential whitespace
        completion = completion.trim();
        return completion;
    }
}
