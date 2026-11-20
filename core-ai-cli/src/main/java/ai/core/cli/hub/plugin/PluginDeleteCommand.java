package ai.core.cli.hub.plugin;

import ai.core.cli.ConsoleWriter;
import ai.core.cli.hub.HubCommandBase;
import ai.core.cli.hub.HubExitCodes;
import ai.core.cli.hub.HubRenderer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * Removes an entry from the registry; the package bytes at the download URL are untouched.
 * Requires {@code plugin.manage}.
 *
 * @author stephen
 */
@Command(name = "delete", description = "Delete a registry entry (the package file itself is not deleted)")
class PluginDeleteCommand extends HubCommandBase {
    @Parameters(index = "0", paramLabel = "id", description = "Registry plugin id")
    String id;

    @Override
    protected Integer execute() {
        pluginClient().delete(id);
        if (json()) {
            HubRenderer.printJson(java.util.Map.of("deleted", id));
        } else {
            ConsoleWriter.println("Deleted registry entry " + id);
        }
        return HubExitCodes.SUCCESS;
    }
}
