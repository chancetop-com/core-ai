package ai.core.example.naixt.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.logging.Logger;

/**
 * @author stephen
 */
public class IdeUtils {
    private static final Logger LOGGER = Logger.getLogger(IdeUtils.class.getName());

    public static String getFileContent(String path) {
        try {
            return Files.readString(Paths.get(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warning("Failed to read file: " + path);
            return "";
        }
    }
}
