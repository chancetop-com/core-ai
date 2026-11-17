package ai.core.cli.agent;

import ai.core.agent.Agent;
import ai.core.cli.config.ModelRegistry;
import ai.core.cli.config.ProviderConfigurator;
import ai.core.cli.config.ServerModelCatalog;
import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.TerminalUI;
import ai.core.llm.LLMProviderType;
import ai.core.llm.LLMProviders;

import java.util.List;
import java.util.Objects;

class ModelPicker {
    private final TerminalUI ui;
    private final Agent agent;
    private final LLMProviders llmProviders;
    private final ModelRegistry modelRegistry;
    private final ServerModelCatalog serverModels;

    ModelPicker(TerminalUI ui, Agent agent, LLMProviders llmProviders, ModelRegistry modelRegistry) {
        this(ui, agent, llmProviders, modelRegistry, ServerModelCatalog.instance());
    }

    ModelPicker(TerminalUI ui, Agent agent, LLMProviders llmProviders, ModelRegistry modelRegistry,
                ServerModelCatalog serverModels) {
        this.ui = ui;
        this.agent = agent;
        this.llmProviders = llmProviders;
        this.modelRegistry = modelRegistry;
        this.serverModels = serverModels;
    }

    void showModelPicker() {
        String currentModel = getCurrentModelName();
        var currentProviderType = llmProviders.getProviderType(agent.getLLMProvider());
        ui.printStreamingChunk("\n  " + AnsiTheme.PROMPT + "Current model: " + AnsiTheme.RESET + currentModel + "\n\n");
        var remoteModels = serverModels.models();
        var entries = ModelChoices.build(currentModel, currentProviderType, modelRegistry.getAllEntries(), remoteModels);
        printChoices(entries, remoteModels != null && !remoteModels.isEmpty());
        ui.printStreamingChunk(AnsiTheme.MUTED + "  Select (1-" + entries.size() + "), a/b/c, model name, or 'q' to cancel: " + AnsiTheme.RESET);
        var line = ui.readRawLine();
        if (line == null || "q".equalsIgnoreCase(line.trim())) return;
        handleInput(line.trim(), entries, currentModel);
    }

    private void printChoices(List<ModelChoices.ModelChoice> entries, boolean serverCatalog) {
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            String providerTag = entry.providerLabel() == null ? ""
                    : AnsiTheme.MUTED + " [" + entry.providerLabel() + "]" + AnsiTheme.RESET;
            String marker = entry.active() ? AnsiTheme.SUCCESS + " (active)" + AnsiTheme.RESET : "";
            ui.printStreamingChunk(String.format("  %s%2d)%s %s%s%s%n",
                    AnsiTheme.PROMPT, i + 1, AnsiTheme.RESET, entry.model(), providerTag, marker));
        }
        if (serverCatalog) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "\n  Models served by your logged-in server; local models are appended." + AnsiTheme.RESET + "\n");
        }
        ui.printStreamingChunk(String.format("%n  %s a)%s Add model  %s b)%s New provider  %s c)%s Remove model%n%n",
                AnsiTheme.CMD_NAME, AnsiTheme.RESET, AnsiTheme.CMD_NAME, AnsiTheme.RESET, AnsiTheme.CMD_NAME, AnsiTheme.RESET));
    }

    private void handleInput(String input, List<ModelChoices.ModelChoice> entries, String currentModel) {
        var configurator = new ProviderConfigurator(ui, llmProviders, modelRegistry);
        if ("a".equalsIgnoreCase(input)) {
            configurator.addModelToProvider();
            return;
        }
        if ("b".equalsIgnoreCase(input)) {
            var result = configurator.configure();
            if (result != null) applySelectedModel(result.type(), result.model());
            return;
        }
        if ("c".equalsIgnoreCase(input)) {
            configurator.removeModelFromProvider();
            return;
        }
        try {
            int idx = Integer.parseInt(input);
            if (idx >= 1 && idx <= entries.size()) {
                var picked = entries.get(idx - 1);
                if (picked.serverServed()) {
                    switchServerModel(currentModel, picked.model(), picked.providerLabel());
                } else {
                    switchModel(currentModel, picked.model(), picked.providerType());
                }
                return;
            }
        } catch (NumberFormatException ignored) {
            // treat as model name
        }
        if (!input.isBlank()) {
            switchModel(currentModel, input, null);
        }
    }

    void switchModel(String currentModel, String newModel, LLMProviderType providerType) {
        var currentProviderType = llmProviders.getProviderType(agent.getLLMProvider());
        if (Objects.equals(currentModel, newModel) && (providerType == null || providerType == currentProviderType)) {
            ui.printStreamingChunk("\n  " + AnsiTheme.MUTED + "Already using " + newModel + AnsiTheme.RESET + "\n\n");
            return;
        }
        var resolvedType = providerType != null ? providerType : modelRegistry.getProviderType(newModel);
        if (resolvedType == null) {
            var remote = serverModels.find(newModel);
            if (remote != null) {
                switchServerModel(currentModel, newModel, remote.providerName);
                return;
            }
            ui.printStreamingChunk("\n  " + AnsiTheme.WARNING + "!" + AnsiTheme.RESET + " Model not in registry, using current provider.\n");
        } else {
            var provider = llmProviders.getProvider(resolvedType);
            if (provider == null) {
                ui.printStreamingChunk("\n  " + AnsiTheme.WARNING + "!" + AnsiTheme.RESET
                        + " Provider is not configured: " + resolvedType.getName() + "\n\n");
                return;
            }
            agent.setLlmProvider(provider);
            new ProviderConfigurator(ui, llmProviders, modelRegistry).saveActiveModel(resolvedType, newModel);
        }
        agent.setModel(newModel);
        updateCompressionModel();
        ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "✓" + AnsiTheme.RESET + " Model switched: "
                + currentModel + " → " + AnsiTheme.PROMPT + newModel + AnsiTheme.RESET + "\n");
        ui.printStreamingChunk(AnsiTheme.MUTED + "  Restart CLI to persist across sessions.\n\n" + AnsiTheme.RESET);
    }

    void switchServerModel(String currentModel, String newModel, String providerName) {
        var litellm = llmProviders.getProvider(LLMProviderType.LITELLM);
        if (litellm == null) {
            ui.printStreamingChunk("\n  " + AnsiTheme.WARNING + "!" + AnsiTheme.RESET
                    + " Server models need a login first (run /login).\n\n");
            return;
        }
        if (Objects.equals(currentModel, newModel)
                && llmProviders.getProviderType(agent.getLLMProvider()) == LLMProviderType.LITELLM) {
            ui.printStreamingChunk("\n  " + AnsiTheme.MUTED + "Already using " + newModel + AnsiTheme.RESET + "\n\n");
            return;
        }
        agent.setLlmProvider(litellm);
        agent.setModel(newModel);
        updateCompressionModel();
        var origin = providerName == null ? "server" : "server, " + providerName;
        ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "✓" + AnsiTheme.RESET + " Model switched: "
                + currentModel + " → " + AnsiTheme.PROMPT + newModel + AnsiTheme.RESET
                + AnsiTheme.MUTED + " (" + origin + ")\n");
        ui.printStreamingChunk(AnsiTheme.MUTED + "  This session only; agent.properties is unchanged.\n\n" + AnsiTheme.RESET);
    }

    private void applySelectedModel(LLMProviderType providerType, String model) {
        agent.setLlmProvider(llmProviders.getProvider(providerType));
        agent.setModel(model);
        updateCompressionModel();
    }

    private void updateCompressionModel() {
        var compression = agent.getCompression();
        if (compression != null) {
            compression.updateModel(agent.getLLMProvider(), getCurrentModelName());
        }
    }

    String getCurrentModelName() {
        return agent.getModel() != null ? agent.getModel() : agent.getLLMProvider().config.getModel();
    }
}
