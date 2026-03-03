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

    public ProviderConfigurator(TerminalUI ui, LLMProviders llmProviders) {
        this.ui = ui;
        this.llmProviders = llmProviders;
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

    public record Result(LLMProviderType type, String model) {
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

    private String configureProvider(LLMProviderType type) {
        String defaultBase = getDefaultApiBase(type);
        ui.printStreamingChunk("  API Base [" + AnsiTheme.MUTED + defaultBase + AnsiTheme.RESET + "]: ");
        var baseLine = ui.readRawLine();
        String apiBase = isBlank(baseLine) ? defaultBase : baseLine.trim();

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
        saveToFile(type, apiBase, keyLine.trim(), model);
        ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "✓" + AnsiTheme.RESET + " Provider "
                + type.getName() + " configured.\n\n");
        return model;
    }

    private void saveToFile(LLMProviderType type, String apiBase, String apiKey, String model) {
        try {
            var props = loadProperties();
            String prefix = type.getName();
            props.setProperty(prefix + ".api.base", apiBase);
            props.setProperty(prefix + ".api.key", apiKey);
            props.setProperty(prefix + ".model", model);
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
            case OPENAI -> "https://api.openai.com/v1";
            case LITELLM -> "https://openrouter.ai/api/v1";
            default -> "https://api.openai.com/v1";
        };
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
