package ai.core.cli.config;

import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.TerminalUI;
import ai.core.llm.LLMProviderConfig;
import ai.core.llm.LLMProviderType;
import ai.core.llm.LLMProviders;
import ai.core.llm.providers.LiteLLMProvider;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Interactive provider configuration from /model command.
 *
 * @author xander
 */
public class ProviderConfigurator {

    private static final Path CONFIG_FILE = Path.of(System.getProperty("user.home"), ".core-ai", "agent.properties");

    private final TerminalUI ui;
    private final LLMProviders llmProviders;
    private final ModelRegistry modelRegistry;

    public ProviderConfigurator(TerminalUI ui, LLMProviders llmProviders, ModelRegistry modelRegistry) {
        this.ui = ui;
        this.llmProviders = llmProviders;
        this.modelRegistry = modelRegistry;
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
            var props = loadProperties();
            props.setProperty(type.getName() + ".model", model);
            storeProperties(props);
        } catch (IOException e) {
            ui.printStreamingChunk(AnsiTheme.WARNING + "  Failed to save model: " + e.getMessage() + AnsiTheme.RESET + "\n");
        }
    }

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
        String defaultModel = LLMProviders.getProviderDefaultChatModel(type);
        ui.printStreamingChunk("  Model [" + AnsiTheme.MUTED + defaultModel + AnsiTheme.RESET + "]: ");
        var modelLine = ui.readRawLine();
        String model = isBlank(modelLine) ? defaultModel : modelLine.trim();
        modelRegistry.addModel(model, type);
        saveModelToFile(type, model);
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
        modelRegistry.removeModel(target.model(), target.providerType());
        removeModelFromFile(target.providerType(), target.model());
        ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "✓" + AnsiTheme.RESET + " Model "
                + target.model() + " removed from " + target.providerType().getName() + ".\n\n");
    }

    private void removeModelFromFile(LLMProviderType type, String model) {
        try {
            var props = loadProperties();
            String prefix = type.getName();
            String existing = props.getProperty(prefix + ".models", "");
            if (!existing.isBlank()) {
                String updated = Arrays.stream(existing.split(","))
                        .map(String::trim)
                        .filter(m -> !m.equals(model))
                        .reduce((a, b) -> a + "," + b)
                        .orElse("");
                if (updated.isEmpty()) {
                    props.remove(prefix + ".models");
                } else {
                    props.setProperty(prefix + ".models", updated);
                }
                storeProperties(props);
            }
        } catch (IOException e) {
            ui.printStreamingChunk(AnsiTheme.WARNING + "  Failed to save config: " + e.getMessage() + AnsiTheme.RESET + "\n");
        }
    }

    private void saveModelToFile(LLMProviderType type, String model) {
        try {
            var props = loadProperties();
            String prefix = type.getName();
            String existing = props.getProperty(prefix + ".models", "");
            boolean alreadyPresent = !existing.isBlank()
                    && Arrays.stream(existing.split(",")).map(String::trim).anyMatch(m -> m.equals(model));
            if (!alreadyPresent) {
                String updated = existing.isBlank() ? model : existing + "," + model;
                props.setProperty(prefix + ".models", updated);
                storeProperties(props);
            }
        } catch (IOException e) {
            ui.printStreamingChunk(AnsiTheme.WARNING + "  Failed to save config: " + e.getMessage() + AnsiTheme.RESET + "\n");
        }
    }

    private boolean hasFixedApiBase(LLMProviderType type) {
        return type == LLMProviderType.DEEPSEEK || type == LLMProviderType.OPENROUTER;
    }

    private String configureProvider(LLMProviderType type) {
        String defaultBase = getDefaultApiBase(type);
        String apiBase;
        if (hasFixedApiBase(type)) {
            ui.printStreamingChunk("  API Base: " + AnsiTheme.MUTED + defaultBase + AnsiTheme.RESET + "\n");
            apiBase = defaultBase;
        } else {
            ui.printStreamingChunk("  API Base [" + AnsiTheme.MUTED + defaultBase + AnsiTheme.RESET + "]: ");
            var baseLine = ui.readRawLine();
            apiBase = isBlank(baseLine) ? defaultBase : baseLine.trim();
        }

        ui.printStreamingChunk("  API Key: ");
        var keyLine = ui.readRawLine();
        if (isBlank(keyLine)) {
            ui.printStreamingChunk(AnsiTheme.ERROR + "  API key is required.\n" + AnsiTheme.RESET);
            return null;
        }

        String defaultModel = LLMProviders.getProviderDefaultChatModel(type);
        ui.printStreamingChunk("  Model [" + AnsiTheme.MUTED + defaultModel + AnsiTheme.RESET + "]: ");
        var modelLine = ui.readRawLine();
        String model = isBlank(modelLine) ? defaultModel : modelLine.trim();

        var config = new LLMProviderConfig(model, 0.7d, null);
        var provider = new LiteLLMProvider(config, apiBase, keyLine.trim());
        llmProviders.addProvider(type, provider);
        modelRegistry.addModel(model, type);
        saveToFile(type, apiBase, keyLine.trim(), model);
        ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "✓" + AnsiTheme.RESET + " Provider "
                + type.getName() + " configured.\n\n");
        return model;
    }

    private void saveToFile(LLMProviderType type, String apiBase, String apiKey, String model) {
        try {
            var props = loadProperties();
            String prefix = type.getName();
            if (!hasFixedApiBase(type)) {
                props.setProperty(prefix + ".api.base", apiBase);
            }
            props.setProperty(prefix + ".api.key", apiKey);
            props.setProperty(prefix + ".model", model);
            String existing = props.getProperty(prefix + ".models", "");
            boolean alreadyPresent = !existing.isBlank()
                    && Arrays.stream(existing.split(",")).map(String::trim).anyMatch(m -> m.equals(model));
            if (!alreadyPresent) {
                String updated = existing.isBlank() ? model : existing + "," + model;
                props.setProperty(prefix + ".models", updated);
            }
            storeProperties(props);
        } catch (IOException e) {
            ui.printStreamingChunk(AnsiTheme.WARNING + "  Failed to save config: " + e.getMessage() + AnsiTheme.RESET + "\n");
        }
    }

    private Properties loadProperties() throws IOException {
        var props = new Properties();
        if (Files.exists(CONFIG_FILE)) {
            try (InputStream is = Files.newInputStream(CONFIG_FILE)) {
                props.load(is);
            }
        }
        return props;
    }

    private void storeProperties(Properties props) throws IOException {
        try (OutputStream os = Files.newOutputStream(CONFIG_FILE)) {
            props.store(os, null);
        }
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
