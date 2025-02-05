package ai.core.lsp.service;

/**
 * @author stephen
 */
public record LanguageServerConfig(String lsHome,
                                   String jdkHome,
                                   String workspace) {
    public static final long IDLE_TIMEOUT = 10 * 60 * 1000;
}
