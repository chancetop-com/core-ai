package ai.core.cli.hub.report;

import ai.core.cli.ConsoleWriter;
import ai.core.cli.hub.HubExitCodes;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * {@code core-ai-cli report} — publish locally generated reports into core-ai-server projects so they are
 * browsable by business line (subject) and shareable by link instead of by sending HTML files around.
 *
 * @author stephen
 */
@Command(name = "report", description = "Publish local reports into core-ai-server project subjects",
        subcommands = {ReportPushCommand.class, ReportProjectsCommand.class})
public class ReportCommand implements Callable<Integer> {
    @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show help")
    boolean helpRequested;

    @Override
    public Integer call() {
        ConsoleWriter.println("Usage: core-ai-cli report <push|projects> [options]");
        ConsoleWriter.println("  push <file> --project <id|name> --subject <id|name>   upload a report into a project subject");
        ConsoleWriter.println("  projects                                              list projects and subjects");
        ConsoleWriter.println("Run 'core-ai-cli report <subcommand> --help' for details.");
        return HubExitCodes.USAGE;
    }
}
