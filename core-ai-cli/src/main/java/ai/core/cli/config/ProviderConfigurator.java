package ai.core.cli.config;

import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.TerminalUI;
import ai.core.llm.LLMProviderConfig;
import ai.core.llm.LLMProviderType;
import ai.core.llm.LLMProviders;
import ai.core.llm.providers.LiteLLMProvider;

import java.util.List;

/**
 * Interactive provider configuration from /model. Direct providers are written to
 * {@code ~/.core-ai/llm-providers.json}; {@code agent.properties} is no longer used for credentials.
 *
 * @author xander
 */
public class ProviderConfigurator {

    private final TerminalUI ui;
    private final LLMProviders llmProviders;
    private final ModelRegistry modelRegistry;
    private final LlmProviderStore store;

    public ProviderConfigurator(TerminalUI ui, LLMProviders llmProviders, ModelRegistry modelRegistry) {
        this(ui, llmProviders, modelRegistry, LlmProviderStore.load());
    }

    public ProviderConfigurator(TerminalUI ui, LLMProviders llmProviders, ModelRegistry modelRegistry,
                                LlmProviderStore store) {
        this.ui = ui;
        this.llmProviders = llmProviders;
        this.modelRegistry = modelRegistry;
        this.store = store;
    }

    public Result configure() {
        ui.printStreamingChunk("\n  " + AnsiTheme.PROMPT + "Add Provider" + AnsiTheme.RESET + "\n\n");
        LLMProviderType[] types = LLMProviderType.values();
        printProviderTypes(types);
        LLMProviderType selectedType = readProviderType(types);
        if (selectedType == null) return null;
        String model = configureProvider(selectedType);
        if (model == null) return null;
        return new Result(selectedType, model);
    }

    private void printProviderTypes(LLMProviderType[] types) {
        for (int i = 0; i < types.length; i++) {
            String mark = llmProviders.getProvider(types[i]) != null ? AnsiTheme.SUCCESS + " ✓" + AnsiTheme.RESET : "";
            ui.printStreamingChunk(String.format("  %s%d)%s %s%s%n", AnsiTheme.PROMPT, i + 1, AnsiTheme.RESET, types[i].getName(), mark));
        }
        ui.printStreamingChunk("\n");
    }

    private LLMProviderType readProviderType(LLMProviderType[] types) {
        ui.printStreamingChunk(AnsiTheme.MUTED + "  Select provider (1-" + types.length + "): " + AnsiTheme.RESET);
        var line = ui.readRawLine();
        if (line == null) return null;
        try {
            int idx = Integer.parseInt(line.trim());
            if (idx >= 1 && idx <= types.length) return types[idx - 1];
        } catch (NumberFormatException ignored) {
            // invalid input
        }
        return null;
    }

    public void saveActiveModel(LLMProviderType type, String model) {
        try {
            if (type == LLMProviderType.LITELLM) {
                store.setDefaultConnection("server");
                store.setServerModel(model);
            } else {
                store.setDefaultConnection(type.getName());
                store.setDefaultModel(type, model);
            }
            llmProviders.setDefaultProvider(type);
        } catch (RuntimeException e) {
            ui.printStreamingChunk(AnsiTheme.WARNING + "  Failed to save model: " + e.getMessage() + AnsiTheme.RESET + "\n");
        }
    }

    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings("RV_RETURN_VALUE_IGNORED_NO_SIDE_EFFECT")
    public void addModelToProvider() {
        List<LLMProviderType> configured = llmProviders.getProviderTypes();
        if (configured.isEmpty()) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "  No providers configured.\n" + AnsiTheme.RESET);
            return;
        }
        LLMProviderType type;
        if (configured.size() == 1) {
            type = configured.getFirst();
            ui.printStreamingChunk("\n  " + AnsiTheme.PROMPT + "Add Model to " + type.getName() + AnsiTheme.RESET + "\n\n");
        } else {
            ui.printStreamingChunk("\n  " + AnsiTheme.PROMPT + "Add Model to Provider" + AnsiTheme.RESET + "\n\n");
            for (int i = 0; i < configured.size(); i++) {
                ui.printStreamingChunk(String.format("  %s%d)%s %s%n", AnsiTheme.PROMPT, i + 1, AnsiTheme.RESET, configured.get(i).getName()));
            }
            ui.printStreamingChunk("\n" + AnsiTheme.MUTED + "  Select provider (1-" + configured.size() + "): " + AnsiTheme.RESET);
            var line = ui.readRawLine();
            if (line == null) return;
            int idx;
            try {
                idx = Integer.parseInt(line.trim());
                if (idx < 1 || idx > configured.size()) return;
            } catch (NumberFormatException e) {
                return;
            }
            type = configured.get(idx - 1);
        }
        var profile = store.profile(type).orElse(null);
        if (profile == null) {
            ui.printStreamingChunk(AnsiTheme.WARNING + "  " + type.getName()
                    + " has no provider-store profile; run 'Add Provider' first.\n" + AnsiTheme.RESET);
            return;
        }
        String defaultModel = profile.effectiveDefaultModel();
        ui.printStreamingChunk("  Model [" + AnsiTheme.MUTED + defaultModel + AnsiTheme.RESET + "]: ");
        var modelLine = ui.readRawLine();
        String model = isBlank(modelLine) ? defaultModel : modelLine.trim();
        addModel(profile, model);
        modelRegistry.addModel(model, type);
        ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "✓" + AnsiTheme.RESET + " Model "
                + model + " added to " + type.getName() + ".\n\n");
        new Result(type, model);
    }

    public void removeModelFromProvider() {
        var allEntries = modelRegistry.getAllEntries();
        if (allEntries.isEmpty()) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "  No models to remove.\n" + AnsiTheme.RESET);
            return;
        }
        ui.printStreamingChunk("\n  " + AnsiTheme.PROMPT + "Remove Model" + AnsiTheme.RESET + "\n\n");
        for (int i = 0; i < allEntries.size(); i++) {
            var entry = allEntries.get(i);
            String tag = AnsiTheme.MUTED + " [" + entry.providerType().getName() + "]" + AnsiTheme.RESET;
            ui.printStreamingChunk(String.format("  %s%d)%s %s%s%n", AnsiTheme.PROMPT, i + 1, AnsiTheme.RESET, entry.model(), tag));
        }
        ui.printStreamingChunk("\n" + AnsiTheme.MUTED + "  Select model to remove (1-" + allEntries.size() + "): " + AnsiTheme.RESET);
        var line = ui.readRawLine();
        if (line == null) return;
        int idx;
        try {
            idx = Integer.parseInt(line.trim());
            if (idx < 1 || idx > allEntries.size()) return;
        } catch (NumberFormatException e) {
            return;
        }
        var target = allEntries.get(idx - 1);
        store.profile(target.providerType()).ifPresent(profile -> removeModel(profile, target.model()));
        modelRegistry.removeModel(target.model(), target.providerType());
        ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "✓" + AnsiTheme.RESET + " Model "
                + target.model() + " removed from " + target.providerType().getName() + ".\n\n");
    }

    private void addModel(LlmProviderProfile profile, String model) {
        if (profile.models == null) profile.models = new java.util.ArrayList<>();
        boolean present = profile.models.stream().anyMatch(item -> item.equals(model));
        if (!present) {
            profile.models.add(model);
            if (profile.defaultModel == null || profile.defaultModel.isBlank()) profile.defaultModel = model;
            store.saveProfile(profile);
        }
    }

    private void removeModel(LlmProviderProfile profile, String model) {
        if (profile.models == null) return;
        profile.models.removeIf(item -> item.equals(model));
        if (model.equals(profile.defaultModel)) {
            profile.defaultModel = profile.models.isEmpty() ? null : profile.models.getFirst();
        }
        store.saveProfile(profile);
    }

    private String configureProvider(LLMProviderType type) {
        String defaultBase = getDefaultApiBase(type);
        ui.printStreamingChunk("  API Base [" + AnsiTheme.MUTED + defaultBase + AnsiTheme.RESET + "]: ");
        var baseLine = ui.readRawLine();
        String apiBase = isBlank(baseLine) ? defaultBase : baseLine.trim();

        ui.printStreamingChunk("  API Key (blank for none): ");
        var keyLine = ui.readRawLine();
        String apiKey = keyLine == null ? "" : keyLine.trim();

        String defaultModel = LLMProviders.getProviderDefaultChatModel(type);
        ui.printStreamingChunk("  Model [" + AnsiTheme.MUTED + defaultModel + AnsiTheme.RESET + "]: ");
        var modelLine = ui.readRawLine();
        String model = isBlank(modelLine) ? defaultModel : modelLine.trim();

        var profile = LlmProviderProfile.preset(type);
        if (profile == null) {
            ui.printStreamingChunk(AnsiTheme.WARNING + "  " + type.getName()
                    + " cannot be configured as a direct provider; use a supported type.\n" + AnsiTheme.RESET);
            return null;
        }
        profile.baseUrl = apiBase;
        profile.apiKey = apiKey;
        profile.defaultModel = model;
        if (profile.models == null) profile.models = new java.util.ArrayList<>();
        if (!profile.models.contains(model)) profile.models.add(model);
        try {
            store.saveProfile(profile);
        } catch (RuntimeException e) {
            ui.printStreamingChunk(AnsiTheme.ERROR + "  " + e.getMessage() + "\n" + AnsiTheme.RESET);
            return null;
        }

        var config = new LLMProviderConfig(model, 0.7d, null);
        var provider = new LiteLLMProvider(config, apiBase, apiKey);
        llmProviders.addProvider(type, provider);
        modelRegistry.addModel(model, type);
        ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "✓" + AnsiTheme.RESET + " Provider "
                + type.getName() + " configured.\n\n");
        return model;
    }

    private String getDefaultApiBase(LLMProviderType type) {
        return switch (type) {
            case DEEPSEEK -> "https://api.deepseek.com/v1";
            case OPENROUTER -> "https://openrouter.ai/api/v1";
            case LITELLM -> "http://localhost:4000";
            default -> "https://api.openai.com/v1";
        };
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    public record Result(LLMProviderType type, String model) {
    }
}
