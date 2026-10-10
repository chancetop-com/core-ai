package ai.core.cli.agent;

import ai.core.prompt.PromptInject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * @author stephen
 */
record CliAgentEnvironmentPrompt(Path workspace, String clientType) implements PromptInject {
    @Override
    public SectionType type() {
        return SectionType.ENVIRONMENT;
    }

    @Override
    public String inject() {
        var gitRepo = Files.isDirectory(workspace.resolve(".git")) ? "yes" : "no";
        var platform = platformName();
        var date = LocalDate.now().format(DateTimeFormatter.ofPattern("EEE MMM dd yyyy"));
        // The parentheses matter: without them the trailing call binds to the last literal only,
        // and the model receives the raw %n/%s template instead of the env block.
        return ("                    <env>%n"
                + "                        Interface: %s%n"
                + "                        Working directory: %s%n"
                + "                        Workspace root folder: %s%n"
                + "                        Is directory a git repo: %s%n"
                + "                        Platform: %s%n"
                + "                        Today's date: %s%n"
                + "                    </env>%n").formatted(interfaceLabel(clientType), workspace.toAbsolutePath(), workspace.toAbsolutePath(), gitRepo, platform, date);
    }

    /**
     * "desktop" is the value the app-server maps for the core-ai-desktop client (see AppServerEngine);
     * every other client (cli today, an unknown handshake name) is described as the CLI until it gets
     * its own label here.
     */
    static String interfaceLabel(String clientType) {
        if ("desktop".equalsIgnoreCase(clientType)) {
            return "core-ai desktop app (GUI window, not a terminal; it manages the embedded "
                    + "browser pane used for web tasks)";
        }
        return "core-ai CLI";
    }

    private static String platformName() {
        var os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) return "darwin";
        if (os.contains("win")) return "win32";
        return "linux";
    }
}
