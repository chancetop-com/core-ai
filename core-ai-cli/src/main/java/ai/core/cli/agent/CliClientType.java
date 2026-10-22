package ai.core.cli.agent;

/**
 * Client types that shape the prompt. The app-server maps the core-ai-desktop handshake name to
 * {@link #DESKTOP}; every other client (CLI, ACP, an unknown name) keeps the CLI identity.
 *
 * @author stephen
 */
final class CliClientType {
    static final String DESKTOP = "desktop";

    static boolean isDesktop(String clientType) {
        return DESKTOP.equalsIgnoreCase(clientType);
    }

    private CliClientType() {
    }
}
