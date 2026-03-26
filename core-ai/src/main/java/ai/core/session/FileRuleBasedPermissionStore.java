package ai.core.session;

import ai.core.session.permission.PermissionRule;
import ai.core.utils.JsonUtil;
import core.framework.api.json.Property;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

public class FileRuleBasedPermissionStore implements ToolPermissionStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(FileRuleBasedPermissionStore.class);

    private final List<String> allowPatterns = new CopyOnWriteArrayList<>();
    private final List<String> denyPatterns = new CopyOnWriteArrayList<>();
    private final Path persistFile;

    public FileRuleBasedPermissionStore() {
        this(null);
    }

    public FileRuleBasedPermissionStore(Path persistFile) {
        this.persistFile = persistFile;
        load();
    }

    @Override
    public void allow(String pattern) {
        denyPatterns.remove(pattern);
        if (!allowPatterns.contains(pattern)) {
            allowPatterns.add(pattern);
            save();
        }
    }

    @Override
    public void deny(String pattern) {
        allowPatterns.remove(pattern);
        if (!denyPatterns.contains(pattern)) {
            denyPatterns.add(pattern);
            save();
        }
    }

    @Override
    public Optional<Boolean> checkPermission(String toolName, Map<String, Object> arguments) {
        // Auto-allow read-only operations
        if (PermissionRule.isReadOnly(toolName, arguments)) {
            LOGGER.debug("auto-allowing read-only operation: tool={}", toolName);
            return Optional.of(true);
        }

        boolean denied = denyPatterns.stream().anyMatch(p -> PermissionRule.matches(p, toolName, arguments));
        if (denied) return Optional.of(false);

        boolean allowed = allowPatterns.stream().anyMatch(p -> PermissionRule.matches(p, toolName, arguments));
        if (allowed) return Optional.of(true);

        return Optional.empty();
    }

    public List<String> getAllowPatterns() {
        return List.copyOf(allowPatterns);
    }

    public List<String> getDenyPatterns() {
        return List.copyOf(denyPatterns);
    }

    private void load() {
        if (persistFile == null || !Files.exists(persistFile)) return;
        try {
            var content = Files.readString(persistFile);
            var domain = JsonUtil.fromJson(PermissionsDomain.class, content);
            if (domain.allow != null) allowPatterns.addAll(domain.allow);
            if (domain.deny != null) denyPatterns.addAll(domain.deny);
            LOGGER.debug("loaded {} allow / {} deny patterns from {}", allowPatterns.size(), denyPatterns.size(), persistFile);
        } catch (IOException e) {
            LOGGER.warn("failed to load permission patterns from {}", persistFile, e);
        }
    }

    private void save() {
        if (persistFile == null) return;
        try {
            Files.createDirectories(persistFile.getParent());
            var domain = new PermissionsDomain();
            domain.allow = new ArrayList<>(allowPatterns);
            domain.deny = new ArrayList<>(denyPatterns);
            Files.writeString(persistFile, JsonUtil.toJson(domain));
            LOGGER.debug("saved {} allow / {} deny patterns to {}", allowPatterns.size(), denyPatterns.size(), persistFile);
        } catch (IOException e) {
            LOGGER.warn("failed to save permission patterns to {}", persistFile, e);
        }
    }

    public static class PermissionsDomain {
        @Property(name = "allow")
        public List<String> allow;
        @Property(name = "deny")
        public List<String> deny;
    }
}
