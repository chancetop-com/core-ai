package ai.core.cli.hub.dataset;

import ai.core.cli.hub.HubCliError;
import ai.core.cli.hub.HubExitCodes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The JSON object text a write operation carries, either inline ({@code --data}) or read from a file
 * ({@code --data-file}), where {@code -} means stdin so a script can pipe a long payload.
 *
 * @author stephen
 */
public class DatasetDataInput {
    public String read(String inline, Path file) {
        var hasInline = inline != null && !inline.isBlank();
        if (hasInline && file != null) {
            throw new HubCliError(HubExitCodes.USAGE, "pass either --data or --data-file, not both");
        }
        if (hasInline) return "-".equals(inline.trim()) ? readStdin() : inline;
        if (file != null) {
            if ("-".equals(file.toString())) return readStdin();
            try {
                return Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new HubCliError(HubExitCodes.USAGE, "cannot read --data-file: " + e.getMessage(), e);
            }
        }
        throw new HubCliError(HubExitCodes.USAGE, "missing data: pass --data <json> or --data-file <path|->");
    }

    private String readStdin() {
        try {
            return new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new HubCliError(HubExitCodes.USAGE, "cannot read stdin: " + e.getMessage(), e);
        }
    }
}
