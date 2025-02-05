package ai.core.tool.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * @author stephen
 */
public class ExecuteShellCommandTool {
    private static final Logger LOGGER = LoggerFactory.getLogger(ExecuteShellCommandTool.class);

    public static String call(List<String> commands) {
        var pb = new ProcessBuilder(commands);
        try {
            var process = pb.start();
            var outputLines = readStream(process.getInputStream());
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                var errorLines = readStream(process.getErrorStream());
                throw new RuntimeException(String.join("\n", errorLines));
            }
            LOGGER.debug(String.join("\n", outputLines));
            return outputLines.isEmpty() ? null : String.join("\n", outputLines);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    private static List<String> readStream(InputStream inputStream) throws Exception {
        List<String> lines = new ArrayList<>();
        try (var reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while (true) {
                line = reader.readLine();
                if (line == null) {
                    break;
                }
                lines.add(line);
            }
        }
        return lines;
    }
}