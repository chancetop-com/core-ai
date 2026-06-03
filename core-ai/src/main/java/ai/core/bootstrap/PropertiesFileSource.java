package ai.core.bootstrap;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/**
 * @author stephen
 */
public class PropertiesFileSource implements PropertySource {
    public static PropertiesFileSource fromFile(Path filePath) {
        var props = new Properties();
        try (var is = Files.newInputStream(filePath)) {
            props.load(is);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new PropertiesFileSource(props);
    }

    public static PropertiesFileSource fromClasspath(String resourcePath) {
        var props = new Properties();
        try (var is = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) throw new IllegalArgumentException("properties file not found on classpath: " + resourcePath);
            props.load(is);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new PropertiesFileSource(props);
    }

    private final Properties properties;

    public PropertiesFileSource(Properties properties) {
        this.properties = properties;
    }

    @Override
    public Optional<String> property(String key) {
        return Optional.ofNullable(properties.getProperty(key));
    }

    public Set<String> propertyNames() {
        return Set.copyOf(properties.stringPropertyNames());
    }

    /**
     * Sets a property on the underlying Properties, for programmatic config
     * merging (e.g. workspace-local MCP config overlay).
     */
    public void putProperty(String key, String value) {
        properties.setProperty(key, value);
    }

    /**
     * Loads workspace-local agent.properties and overlays its values onto this
     * source. Workspace-local values override global defaults. Safe to call
     * when no local config exists (no-op).
     */
    public static void mergeWorkspaceLocal(PropertiesFileSource global, Path workspace) {
        Path localConfig = workspace.resolve(".core-ai").resolve("agent.properties");
        if (!Files.exists(localConfig)) return;
        try (var is = Files.newInputStream(localConfig)) {
            var localProps = new Properties();
            localProps.load(is);
            localProps.forEach((k, v) -> global.putProperty((String) k, (String) v));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load workspace-local config: " + localConfig, e);
        }
    }
}
