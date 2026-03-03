package ai.core.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author stephen
 */
public class ToolPermissionStore {
    private final Logger logger = LoggerFactory.getLogger(ToolPermissionStore.class);
    private final Set<String> approvedTools = ConcurrentHashMap.newKeySet();
    private final Path persistFile;

    public ToolPermissionStore(Path persistFile) {
        this.persistFile = persistFile;
        load();
    }

    public boolean isApproved(String toolName) {
        return approvedTools.contains(toolName);
    }

    public void approve(String toolName) {
        if (approvedTools.add(toolName)) {
            save();
        }
    }

    public Set<String> approvedTools() {
        return Set.copyOf(approvedTools);
    }

    private void load() {
        if (persistFile == null || !Files.exists(persistFile)) return;
        try {
            var lines = Files.readAllLines(persistFile);
            for (var line : lines) {
                var trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    approvedTools.add(trimmed);
                }
            }
            logger.debug("loaded {} approved tools from {}", approvedTools.size(), persistFile);
        } catch (IOException e) {
            logger.warn("failed to load tool permissions from {}", persistFile, e);
        }
    }

    private void save() {
        if (persistFile == null) return;
        try {
            Files.createDirectories(persistFile.getParent());
            Files.write(persistFile, approvedTools.stream().sorted().toList());
            logger.debug("saved {} approved tools to {}", approvedTools.size(), persistFile);
        } catch (IOException e) {
            logger.warn("failed to save tool permissions to {}", persistFile, e);
        }
    }
}
