package ai.core.cli.hub.plugin;

import ai.core.cli.ConsoleWriter;
import ai.core.cli.hub.HubExitCodes;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * {@code core-ai-cli plugin} — discover, inspect, install and publish desktop plugin packages
 * registered on core-ai-server, without starting an agent session.
 *
 * @author stephen
 */
@Command(name = "plugin", description = "Discover, install and publish desktop plugin packages registered on core-ai-server",
        subcommands = {PluginSearchCommand.class, PluginShowCommand.class, PluginInstallCommand.class,
            PluginListCommand.class, PluginRemoveCommand.class, PluginPublishCommand.class, PluginDeleteCommand.class})
public class PluginCommand implements Callable<Integer> {
    @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show help")
    boolean helpRequested;

    @Override
    public Integer call() {
        ConsoleWriter.println("Usage: core-ai-cli plugin <search|show|install|list|remove|publish|delete> [options]");
        ConsoleWriter.println("Run 'core-ai-cli plugin <subcommand> --help' for details.");
        return HubExitCodes.USAGE;
    }
}
