package ai.core.utils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * @author stephen
 */
public class InputStreamUtil {
    public static List<String> readStream(InputStream inputStream) throws IOException {
        var lines = new ArrayList<String>();
        try (var reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while (true) {
                line = reader.readLine();
                if (line == null) break;
                lines.add(line);
            }
        }
        return lines;
    }
}
