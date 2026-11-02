package ai.core.persistence.providers;

import ai.core.persistence.PersistenceProvider;
import ai.core.utils.AtomicFiles;
import core.framework.util.Files;

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
        AtomicFiles.writeString(Paths.get(path(id)), context);
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
        var path = Paths.get(path(id));
        if (!java.nio.file.Files.exists(path)) return Optional.empty();
        return Optional.of(Files.text(path));
    }

    private String path(String id) {
        return directory + "/" + id + ".data";
    }
}
