package ai.core.lsp.service.jdtls;

import ai.core.lsp.service.LanguageServerConfig;
import ai.core.lsp.service.LanguageServerManager;

import java.io.File;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;

/**
 * @author stephen
 */
public class JDTLanguageServerManager extends LanguageServerManager {

    @Override
    public ProcessBuilder setupProcessBuilder(LanguageServerConfig config, String workspace) {
        if (getProcess() != null) {
            throw new IllegalStateException("Language Server is already running!");
        }

        var jarFile = findLauncherJar(config.lsHome());
        var cfgFile = getConfigDir();

        return new ProcessBuilder(
                "java",
                "-jar", jarFile,
                "-configuration", Paths.get(config.lsHome(), cfgFile).toString(),
                "-data", workspace
        );
    }

    private String findLauncherJar(String jdtLsHome) {
        var pluginsDir = new File(jdtLsHome, "plugins");
        if (!pluginsDir.exists() || !pluginsDir.isDirectory()) {
            throw new IllegalStateException("Plugins directory not found: " + pluginsDir.getAbsolutePath());
        }

        var launcherJar = Optional.ofNullable(pluginsDir.listFiles()).flatMap(files -> Arrays.stream(files)
                .filter(file -> file.getName().startsWith("org.eclipse.equinox.launcher_") && file.getName().endsWith(".jar"))
                .max(Comparator.comparing(File::getName)));

        if (launcherJar.isEmpty()) {
            throw new IllegalStateException("No launcher JAR found in plugins directory: " + pluginsDir.getAbsolutePath());
        }

        return launcherJar.get().getAbsolutePath();
    }

    private String getConfigDir() {
        var osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String configDir;

        if (osName.contains("win")) {
            configDir = "config_win";
        } else if (osName.contains("nix") || osName.contains("nux") || osName.contains("aix")) {
            configDir = "config_linux";
        } else if (osName.contains("mac")) {
            configDir = "config_mac";
        } else {
            throw new UnsupportedOperationException("Unsupported operating system: " + osName);
        }

        return configDir;
    }
}
