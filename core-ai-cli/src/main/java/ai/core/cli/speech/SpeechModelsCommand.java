package ai.core.cli.speech;

import ai.core.cli.ConsoleWriter;
import ai.core.cli.hub.HubCommandBase;
import ai.core.cli.hub.HubExitCodes;
import ai.core.cli.hub.HubRenderer;
import picocli.CommandLine.Command;

/**
 * @author stephen
 */
@Command(name = "models", description = "List speech-to-text models enabled on the server")
class SpeechModelsCommand extends HubCommandBase {
    @Override
    protected Integer execute() {
        var credentials = credentials();
        var response = new SpeechClient(credentials.serverUrl(), credentials.apiKey(), credentials.insecure()).models();
        if (json()) {
            HubRenderer.printJson(response);
            return HubExitCodes.SUCCESS;
        }
        if (response.models == null || response.models.isEmpty()) {
            ConsoleWriter.println("speech transcription is not configured on this server");
            return HubExitCodes.SUCCESS;
        }
        for (var model : response.models) {
            var line = new StringBuilder(model.modelId);
            if (model.displayName != null && !model.displayName.equals(model.modelId)) {
                line.append("  ").append(model.displayName);
            }
            if (model.providerName != null) line.append("  (").append(model.providerName).append(')');
            if (model.modelId != null && model.modelId.equals(response.defaultModel)) line.append("  [default]");
            ConsoleWriter.println(line.toString());
        }
        return HubExitCodes.SUCCESS;
    }
}
