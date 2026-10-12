package ai.core.cli.agent;

import ai.core.prompt.PromptInject;

/**
 * Injected when the desktop app enables computer use for this engine ({@code CORE_AI_COMPUTER_USE=1}):
 * the cua CLI is installed and already connected to this machine's desktop (target {@code host} with
 * consent granted), so the gui-automation skill's setup section must not be followed.
 *
 * @author stephen
 */
record CliAgentComputerUsePrompt() implements PromptInject {
    @Override
    public SectionType type() {
        return SectionType.ENVIRONMENT;
    }

    @Override
    public String inject() {
        return """
                The core-ai desktop app that started this engine has computer use enabled: the cua CLI
                (`cua`) is installed and already connected to this machine's desktop (target `host`, consent
                granted), so `cua do ...` commands work as-is.
                For tasks that need a real GUI (native apps, windows), load the gui-automation skill and
                follow its Look -> Act -> Verify workflow.
                Skip the skill's setup section: never run `cua auth login`, `cua host ...`, `cua do-host-consent`,
                or `cua do switch` - the app owns the connection. If a cua command reports a missing target,
                an authentication error, or an unreachable service, stop and ask the user to re-enable
                Computer use in the app's Settings.
                Prefer shell, file and browser-pane routes first; computer use is the last resort. Ask the
                user before actions with external side effects (sending, publishing, deleting, paying).
                """;
    }
}
