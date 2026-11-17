package ai.core.cli.agent;

import ai.core.api.server.gateway.GatewayAvailableModelView;
import ai.core.cli.config.ModelRegistry;
import ai.core.llm.LLMProviderType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Builds the /model picker entries: server catalog first, then local registry models the catalog does
 * not already list, plus the current model when neither source knows it.
 *
 * @author stephen
 */
final class ModelChoices {
    static List<ModelChoice> build(String currentModel, LLMProviderType currentProviderType,
                                   List<ModelRegistry.ModelEntry> localEntries,
                                   List<GatewayAvailableModelView> remoteModels) {
        var choices = new ArrayList<ModelChoice>();
        if (remoteModels != null) {
            for (var model : remoteModels) {
                choices.add(new ModelChoice(model.modelId, model.providerName, LLMProviderType.LITELLM, true,
                        Objects.equals(model.modelId, currentModel)));
            }
        }
        for (var entry : localEntries) {
            if (choices.stream().anyMatch(choice -> Objects.equals(choice.model(), entry.model()))) {
                continue;
            }
            choices.add(new ModelChoice(entry.model(), entry.providerType().getName(), entry.providerType(), false,
                    Objects.equals(entry.model(), currentModel) && entry.providerType() == currentProviderType));
        }
        if (currentModel != null && choices.stream().noneMatch(choice -> Objects.equals(choice.model(), currentModel))) {
            choices.addFirst(new ModelChoice(currentModel, null, null, false, true));
        }
        return choices;
    }

    private ModelChoices() {
    }

    record ModelChoice(String model, String providerLabel, LLMProviderType providerType, boolean serverServed,
                       boolean active) {
    }
}
