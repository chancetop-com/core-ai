package ai.core.persistence.providers;

import ai.core.persistence.PersistenceProvider;
import core.framework.util.Files;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public class FilePersistenceProvider implements PersistenceProvider {
    private final String directory;

    public FilePersistenceProvider() {
        this.directory = Files.tempDir().toString();
        Files.createDir(Paths.get(directory));
    }

    public FilePersistenceProvider(String directory) {
        this.directory = directory;
        Files.createDir(Paths.get(directory));
    }

    @Override
    public void save(String id, String context) {
        try {
            java.nio.file.Files.writeString(Paths.get(path(id)), context);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save context to file", e);
        }
    }

    @Override
    public void clear() {
        Files.deleteDir(Paths.get(directory));
    }

    @Override
    public void delete(List<String> ids) {
        ids.forEach(v -> Files.delete(Paths.get(path(v))));
    }

    @Override
    public Optional<String> load(String id) {
        return Optional.of(Files.text(Paths.get(path(id))));
    }

    public String path(String id) {
        return directory + "/" + id + ".data";
    }
}
