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
}
