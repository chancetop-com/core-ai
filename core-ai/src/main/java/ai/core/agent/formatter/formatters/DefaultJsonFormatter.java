package ai.core.agent.formatter.formatters;

import ai.core.agent.formatter.Formatter;

/**
 * @author stephen
 */
public class DefaultJsonFormatter implements Formatter {
    @Override
    public String formatter(String rst) {
        var completion = rst;
        // Remove leading and trailing whitespace
        completion = completion.trim();

        // Check for and remove triple backticks and "json" identifier
        if (completion.startsWith("```") && completion.endsWith("```")) {
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
        }
        return completion;
    }
}
