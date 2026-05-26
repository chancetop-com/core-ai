import picocli.CommandLine;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class VersionProvider implements CommandLine.IVersionProvider {
    @Override
    public String[] getVersion() {
        try (InputStream is = VersionProvider.class.getResourceAsStream("/VERSION")) {
            if (is != null) {
                return new String[]{new String(is.readAllBytes(), StandardCharsets.UTF_8).trim()};
            }
        } catch (Exception e) {
            // fall through
        }
        return new String[]{"unknown"};
    }
}
