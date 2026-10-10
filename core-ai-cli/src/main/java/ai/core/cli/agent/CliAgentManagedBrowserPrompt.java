package ai.core.cli.agent;

import ai.core.prompt.PromptInject;

/**
 * Injected when the desktop app spawns the engine ({@code CORE_AI_BROWSER_MANAGED=1}): the browser is
 * hosted by the app, browser-use is already attached to it, and the manual browser lifecycle from the
 * skill's usage section must not be followed.
 *
 * @author stephen
 */
record CliAgentManagedBrowserPrompt() implements PromptInject {
    @Override
    public SectionType type() {
        return SectionType.ENVIRONMENT;
    }

    @Override
    public String inject() {
        return """
                The browser is managed by the core-ai desktop app that started this engine: browser-use is
                already attached to the app's built-in browser pane, and BU_CDP_URL is set in the environment.
                Just run browser-use directly.
                Ignore the dedicated-browser setup from the browser-use skill: never start or attach to another browser
                (Edge/Chrome), never probe CDP ports, and never touch ~/.core-ai/browser-profiles.
                If a site needs a login, ask the user to sign in inside the app's browser panel, then continue.
                """;
    }
}
