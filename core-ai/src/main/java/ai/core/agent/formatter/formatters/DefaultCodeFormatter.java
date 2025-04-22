package ai.core.agent.formatter.formatters;

import ai.core.agent.formatter.Formatter;

import java.util.regex.Pattern;

/**
 * @author stephen
 */
public class DefaultCodeFormatter implements Formatter {

    @Override
    public String formatter(String text) {
        if (text == null) return "";

        var rst = text.trim();

        var pattern = Pattern.compile("```(?:\\w+)?\\n([\\s\\S]*?)\\n```");
        var matcher = pattern.matcher(rst.trim());

        if (matcher.find()) {
            rst = matcher.group(1).trim();
        }

        if (rst.startsWith("```") && !rst.endsWith("```")) {
            int newLine = rst.indexOf('\n');
            if (newLine != -1) {
                rst = rst.substring(newLine).trim();
            }
        }

        if (rst.startsWith("```")) {
            rst = rst.substring(3).trim();
        }

        if (rst.startsWith("```")) {
            rst = rst.substring(3).trim();
        }

        if (rst.endsWith("```")) {
            rst = rst.substring(0, rst.length() - 3).trim();
        }

        if (rst.startsWith("`") || rst.endsWith("`")) {
            pattern = Pattern.compile("`([\\s\\S]*?)`");
            matcher = pattern.matcher(rst.trim());

            if (matcher.find()) {
                rst = matcher.group(1).trim();
            }
        }

        return rst.trim();
    }
}
