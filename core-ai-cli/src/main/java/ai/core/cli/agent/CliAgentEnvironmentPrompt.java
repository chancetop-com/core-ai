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
record CliAgentEnvironmentPrompt(Path workspace) implements PromptInject {
    @Override
    public SectionType type() {
        return SectionType.ENVIRONMENT;
    }

    @Override
    public String inject() {
        var gitRepo = Files.isDirectory(workspace.resolve(".git")) ? "yes" : "no";
        var platform = platformName();
        var date = LocalDate.now().format(DateTimeFormatter.ofPattern("EEE MMM dd yyyy"));
        return "                    <env>%n"
                + "                        Working directory: %s%n"
                + "                        Workspace root folder: %s%n"
                + "                        Is directory a git repo: %s%n"
                + "                        Platform: %s%n"
                + "                        Today's date: %s%n"
                + "                    </env>%n".formatted(workspace.toAbsolutePath(), workspace.toAbsolutePath(), gitRepo, platform, date);
    }

    private static String platformName() {
        var os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) return "darwin";
        if (os.contains("win")) return "win32";
        return "linux";
    }
}
