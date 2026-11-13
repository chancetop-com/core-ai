package ai.core.cli.speech;

import ai.core.cli.ConsoleWriter;
import ai.core.cli.hub.HubCommandBase;
import ai.core.cli.hub.HubExitCodes;
import ai.core.cli.hub.HubRenderer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * @author stephen
 */
@Command(name = "models", description = "List speech models enabled on the server")
class SpeechModelsCommand extends HubCommandBase {
    @Option(names = "--synthesis", description = "List text-to-speech models instead of transcription models")
    boolean synthesis;

    @Override
    protected Integer execute() {
        var credentials = credentials();
        var client = new SpeechClient(credentials.serverUrl(), credentials.apiKey(), credentials.insecure());
        if (synthesis) {
            listSynthesis(client);
            return HubExitCodes.SUCCESS;
        }
        var response = client.models();
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

    private void listSynthesis(SpeechClient client) {
        var response = client.synthesisModels();
        if (json()) {
            HubRenderer.printJson(response);
            return;
        }
        if (response.models == null || response.models.isEmpty()) {
            ConsoleWriter.println("speech synthesis is not configured on this server");
            return;
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
    }
}
