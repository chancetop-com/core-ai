package ai.core.cli.agent;

import ai.core.agent.Agent;
import ai.core.cli.config.ModelRegistry;
import ai.core.cli.config.ProviderConfigurator;
import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.TerminalUI;
import ai.core.llm.LLMProviderType;
import ai.core.llm.LLMProviders;

class ModelPicker {
    private final TerminalUI ui;
    private final Agent agent;
    private final LLMProviders llmProviders;
    private final ModelRegistry modelRegistry;

    ModelPicker(TerminalUI ui, Agent agent, LLMProviders llmProviders, ModelRegistry modelRegistry) {
        this.ui = ui;
        this.agent = agent;
        this.llmProviders = llmProviders;
        this.modelRegistry = modelRegistry;
    }

    void showModelPicker() {
        String currentModel = getCurrentModelName();
        var currentProviderType = llmProviders.getProviderType(agent.getLLMProvider());
        ui.printStreamingChunk("\n  " + AnsiTheme.PROMPT + "Current model: " + AnsiTheme.RESET + currentModel + "\n\n");
        var entries = new java.util.ArrayList<>(modelRegistry.getAllEntries());
        if (entries.stream().noneMatch(e -> e.model().equals(currentModel))) {
            entries.addFirst(new ModelRegistry.ModelEntry(currentModel, modelRegistry.getProviderType(currentModel)));
        }
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            String providerTag = AnsiTheme.MUTED + " [" + entry.providerType().getName() + "]" + AnsiTheme.RESET;
            boolean isActive = entry.model().equals(currentModel) && entry.providerType() == currentProviderType;
            String marker = isActive ? AnsiTheme.SUCCESS + " (active)" + AnsiTheme.RESET : "";
            ui.printStreamingChunk(String.format("  %s%2d)%s %s%s%s%n",
                    AnsiTheme.PROMPT, i + 1, AnsiTheme.RESET, entry.model(), providerTag, marker));
        }
        ui.printStreamingChunk(String.format("%n  %s a)%s Add model  %s b)%s New provider  %s c)%s Remove model%n%n",
                AnsiTheme.CMD_NAME, AnsiTheme.RESET, AnsiTheme.CMD_NAME, AnsiTheme.RESET, AnsiTheme.CMD_NAME, AnsiTheme.RESET));
        ui.printStreamingChunk(AnsiTheme.MUTED + "  Select (1-" + entries.size() + "), a/b/c, model name, or 'q' to cancel: " + AnsiTheme.RESET);
        var line = ui.readRawLine();
        if (line == null || "q".equalsIgnoreCase(line.trim())) return;
        String input = line.trim();
        var configurator = new ProviderConfigurator(ui, llmProviders, modelRegistry);
        if ("a".equalsIgnoreCase(input)) {
            configurator.addModelToProvider();
            return;
        } else if ("b".equalsIgnoreCase(input)) {
            var result = configurator.configure();
            if (result != null) {
                agent.setLlmProvider(llmProviders.getProvider(result.type()));
                agent.setModel(result.model());
            }
            return;
        } else if ("c".equalsIgnoreCase(input)) {
            configurator.removeModelFromProvider();
            return;
        }
        try {
            int idx = Integer.parseInt(input);
            if (idx >= 1 && idx <= entries.size()) {
                var picked = entries.get(idx - 1);
                switchModel(currentModel, picked.model(), picked.providerType());
                return;
            }
        } catch (NumberFormatException ignored) {
            // treat as model name
        }
        if (!input.isBlank() && modelRegistry.getProviderType(input) != null) {
            switchModel(currentModel, input, null);
        }
    }

    void switchModel(String currentModel, String newModel, LLMProviderType providerType) {
        var currentProviderType = llmProviders.getProviderType(agent.getLLMProvider());
        if (currentModel.equals(newModel) && (providerType == null || providerType == currentProviderType)) {
            ui.printStreamingChunk("\n  " + AnsiTheme.MUTED + "Already using " + newModel + AnsiTheme.RESET + "\n\n");
            return;
        }
        var resolvedType = providerType != null ? providerType : modelRegistry.getProviderType(newModel);
        if (resolvedType != null) {
            agent.setLlmProvider(llmProviders.getProvider(resolvedType));
            new ProviderConfigurator(ui, llmProviders, modelRegistry).saveActiveModel(resolvedType, newModel);
        } else {
            ui.printStreamingChunk("\n  " + AnsiTheme.WARNING + "!" + AnsiTheme.RESET + " Model not in registry, using current provider.\n");
        }
        agent.setModel(newModel);
        ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "✓" + AnsiTheme.RESET + " Model switched: "
                + currentModel + " → " + AnsiTheme.PROMPT + newModel + AnsiTheme.RESET + "\n");
        ui.printStreamingChunk(AnsiTheme.MUTED + "  Restart CLI to persist across sessions.\n\n" + AnsiTheme.RESET);
    }

    String getCurrentModelName() {
        return agent.getModel() != null ? agent.getModel() : agent.getLLMProvider().config.getModel();
    }
}
