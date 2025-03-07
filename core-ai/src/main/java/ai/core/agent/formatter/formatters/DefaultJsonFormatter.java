package ai.core.agent.formatter.formatters;

import ai.core.agent.formatter.Formatter;
import core.framework.internal.json.JSONMapper;
import core.framework.json.JSON;
import org.apache.commons.lang3.StringUtils;

import java.util.Map;

import static com.fasterxml.jackson.core.json.JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS;

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
        completion = StringUtils.strip(completion, " \n\r\t");

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

        if (dirty) {
            completion = dirtyJsonFormatter(completion);
        }

        return completion;
    }

    private String dirtyJsonFormatter(String completion) {
        try {
            JSON.fromJSON(Map.class, completion);
            return completion;
        } catch (Exception e) {
            if (e.getMessage().contains("Illegal unquoted character")) {
                JSONMapper.OBJECT_MAPPER.enable(ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature());
                var j = JSON.fromJSON(Map.class, completion);
                JSONMapper.OBJECT_MAPPER.disable(ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature());
                return JSON.toJSON(j);
            }
            return completion;
        }
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
