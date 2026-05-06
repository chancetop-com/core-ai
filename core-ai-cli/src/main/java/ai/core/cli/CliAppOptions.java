package ai.core.cli;

import java.nio.file.Path;

/**
 * @author stephen
 */
public record CliAppOptions(Path configFile, String modelOverride, String prompt,
                            boolean autoApproveAll, boolean continueSession,
                            boolean resume, Path workspace) {
}
