package ai.core.cli.agent;

import ai.core.agent.profile.AgentProfile;
import ai.core.agent.profile.AgentProfileRegistry;
import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.TerminalUI;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.ResponseFormat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Interactive guided agent creation wizard.
 *
 * @author lim chen
 */
class CreateAgentCommandHandler {

    private static final String LLM_SYSTEM_PROMPT = """
            You are an agent designer. Given a user's description of what they want an AI agent to do, \
            generate the agent configuration.

            Rules:
            - name: kebab-case, 2-5 words, descriptive (e.g., "code-reviewer", "api-doc-generator")
            - description: one sentence describing when to use this agent. Must start with a verb \
            or "A"/"An" and fit on one line
            - systemPrompt: detailed instructions defining the agent's role, expertise, tone, \
            constraints, and workflow. Write in second person ("You are..."). \
            Include what tools it should prefer and how it should structure its responses. \
            Keep it concise but thorough (3-8 paragraphs).

            Output ONLY a JSON object with exactly these keys (no markdown fences, no other text):
            {
              "name": "kebab-case-agent-name",
              "description": "One-line description of when to use this agent",
              "systemPrompt": "Detailed system prompt defining the agent's behavior..."
            }""";

    private final TerminalUI ui;
    private final LLMProvider llmProvider;
    private final String model;
    private final Path workspace;
    private final AgentProfileRegistry registry;

    CreateAgentCommandHandler(TerminalUI ui, LLMProvider llmProvider, String model,
                              Path workspace, AgentProfileRegistry registry) {
        this.ui = ui;
        this.llmProvider = llmProvider;
        this.model = model;
        this.workspace = workspace;
        this.registry = registry;
    }

    void handle() {
        ui.printStreamingChunk("\n" + AnsiTheme.PROMPT + "Create Agent" + AnsiTheme.RESET
                + AnsiTheme.MUTED + " (describe what the agent should do)" + AnsiTheme.RESET + "\n\n");

        String input = ui.readInput("  Describe: ");
        if (input == null || input.isBlank()) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "  Cancelled.\n" + AnsiTheme.RESET);
            return;
        }

        ui.printStreamingChunk(AnsiTheme.MUTED + "\n  Generating agent profile..." + AnsiTheme.RESET + "\n");

        GeneratedConfig config;
        try {
            config = generateConfig(input);
        } catch (Exception e) {
            ui.printStreamingChunk(AnsiTheme.ERROR + "  Failed to generate agent: " + e.getMessage() + "\n" + AnsiTheme.RESET);
            return;
        }

        if (config == null || config.name == null || config.name.isBlank()) {
            ui.printStreamingChunk(AnsiTheme.ERROR + "  Failed to generate a valid agent profile. Try again with a clearer description.\n" + AnsiTheme.RESET);
            return;
        }

        String name = config.name;
        String description = config.description;
        String systemPrompt = config.systemPrompt;

        while (true) {
            printPreview(name, description, systemPrompt);
            ui.printStreamingChunk("\n  [Y] Save  [N] Cancel  ["
                    + AnsiTheme.CMD_NAME + "E" + AnsiTheme.RESET + "] Edit fields\n");
            String choice = ui.readRawLine("  ❯ ");
            String lower = choice != null ? choice.trim().toLowerCase(Locale.ROOT) : "";

            if (lower.isEmpty() || lower.startsWith("y")) {
                break;
            }
            if (lower.startsWith("n")) {
                ui.printStreamingChunk(AnsiTheme.MUTED + "  Cancelled.\n" + AnsiTheme.RESET);
                return;
            }
            if (lower.startsWith("e")) {
                name = editField("name", name);
                description = editField("description", description);
                systemPrompt = editField("systemPrompt", systemPrompt);
            }
        }

        saveAgent(name, description, systemPrompt);
    }

    private GeneratedConfig generateConfig(String userDescription) {
        String prompt = "User wants an agent that: " + userDescription;
        return llmProvider.completionFormat(LLM_SYSTEM_PROMPT, prompt, model,
                GeneratedConfig.class, ResponseFormat.jsonObject(), null);
    }

    private String editField(String fieldName, String current) {
        int maxLen = 80;
        String preview;
        if (current != null && current.length() > maxLen) {
            preview = current.substring(0, maxLen) + "...";
        } else {
            preview = current;
        }
        ui.printStreamingChunk("  Current " + fieldName + ": "
                + AnsiTheme.MUTED + preview + AnsiTheme.RESET + "\n");
        String input = ui.readInput("  New " + fieldName + " (Enter to keep): ");
        if (input == null || input.isBlank()) {
            return current;
        }
        return input;
    }

    private void printPreview(String name, String description, String systemPrompt) {
        ui.printStreamingChunk("\n  " + AnsiTheme.CMD_NAME + name + AnsiTheme.RESET
                + AnsiTheme.MUTED + " — " + (description != null ? description : "") + AnsiTheme.RESET + "\n");

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "  ────────────────────────────\n" + AnsiTheme.RESET);
            int width = ui.getTerminalWidth();
            for (String line : systemPrompt.lines().toList()) {
                String indent = "  " + AnsiTheme.MUTED;
                ui.printStreamingChunk(indent + wrapLine(line, width - 3) + AnsiTheme.RESET + "\n");
            }
            ui.printStreamingChunk(AnsiTheme.MUTED + "  ────────────────────────────\n" + AnsiTheme.RESET);
        }
    }

    private String wrapLine(String line, int maxWidth) {
        if (line.length() <= maxWidth) return line;
        return line.substring(0, maxWidth - 1) + "…";
    }

    private void saveAgent(String nameParam, String description, String systemPrompt) {
        Path agentsDir = workspace.resolve(".core-ai").resolve("agents");
        String name = sanitizeAgentName(nameParam);
        if (name == null) {
            ui.printStreamingChunk(AnsiTheme.ERROR + "  Invalid agent name after sanitization.\n" + AnsiTheme.RESET);
            return;
        }
        Path file = agentsDir.resolve(name + ".md");
        if (Files.exists(file)) {
            ui.printStreamingChunk(AnsiTheme.WARNING + "  Agent '" + name + "' already exists at " + file + "\n" + AnsiTheme.RESET);
            return;
        }
        try {
            Files.createDirectories(agentsDir);
            Files.writeString(file, buildAgentContent(name, description, systemPrompt));
            refreshAgentRegistry();
            ui.printStreamingChunk(AnsiTheme.SUCCESS + "\n  ✓ Created " + file + AnsiTheme.RESET + "\n");
            ui.printStreamingChunk(AnsiTheme.MUTED + "  Use @" + name + " <prompt> to invoke it.\n" + AnsiTheme.RESET);
        } catch (IOException e) {
            ui.printStreamingChunk(AnsiTheme.ERROR + "  Failed to save agent: " + e.getMessage() + "\n" + AnsiTheme.RESET);
        }
    }

    private String sanitizeAgentName(String name) {
        if (!name.matches("[a-zA-Z0-9][a-zA-Z0-9-]*")) {
            var newName = name.replaceAll("[^a-zA-Z0-9-]", "-").replaceAll("-+", "-").replaceAll("^-|-$", "");
            if (newName.isEmpty()) {
                return null;
            }
            return newName;
        }
        return name;
    }

    private String buildAgentContent(String name, String description, String systemPrompt) {
        String desc = description != null && !description.isBlank() ? description : "Custom agent: " + name;
        String prompt = systemPrompt != null ? systemPrompt : "You are " + name + ".";
        String escapedDesc = desc.replace("\"", "\\\"");
        return "---%n"
                + "name: %s%n"
                + "description: \"%s\"%n"
                + "# Optional fields (uncomment to enable):%n"
                + "# model: sonnet%n"
                + "# temperature: 0.8%n"
                + "# maxTurnNumber: 200%n"
                + "# reasoningEffort: low | medium | high | max%n"
                + "# tools:%n"
                + "#   - Read%n"
                + "#   - Bash%n"
                + "#   - Glob%n"
                + "#   - Grep%n"
                + "#   - Write%n"
                + "#   - Edit%n"
                + "#   - task%n"
                + "#   - WebSearch%n"
                + "#   - WebFetch%n"
                + "---%n%n"
                + "%s%n".formatted(name, escapedDesc, prompt);
    }

    private void refreshAgentRegistry() {
        if (registry != null) {
            registry.invalidateCache();
            ui.setAgentProfiles(registry.listAll().stream().map(AgentProfile::name).toList());
        }
    }

    record GeneratedConfig(String name, String description, String systemPrompt) {
    }
}
