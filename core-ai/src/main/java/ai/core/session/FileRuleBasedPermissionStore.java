package ai.core.session;

import ai.core.session.permission.PathExtractor;
import ai.core.session.permission.PermissionRule;
import core.framework.api.json.Property;
import core.framework.json.JSON;
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
    private static final Logger logger = LoggerFactory.getLogger(FileRuleBasedPermissionStore.class);

    private final CopyOnWriteArrayList<PermissionRule> allowRules = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<PermissionRule> denyRules = new CopyOnWriteArrayList<>();
    private final Path persistFile;

    public FileRuleBasedPermissionStore() {
        this(null);
    }

    public FileRuleBasedPermissionStore(Path persistFile) {
        this.persistFile = persistFile;
        load();
    }

    @Override
    public void allow(String toolName, String pathPattern) {
        var rule = new PermissionRule(toolName, pathPattern);
        denyRules.remove(rule);
        if (!allowRules.contains(rule)) {
            allowRules.add(rule);
            save();
        }
    }

    @Override
    public void deny(String toolName, String pathPattern) {
        var rule = new PermissionRule(toolName, pathPattern);
        allowRules.remove(rule);
        if (!denyRules.contains(rule)) {
            denyRules.add(rule);
            save();
        }
    }

    @Override
    public Optional<Boolean> checkPermission(String toolName, Map<String, Object> arguments) {
        var path = PathExtractor.extractPath(toolName, arguments).orElse(null);

        boolean denied = denyRules.stream().anyMatch(r -> r.matches(toolName, path));
        if (denied) return Optional.of(false);

        boolean allowed = allowRules.stream().anyMatch(r -> r.matches(toolName, path));
        if (allowed) return Optional.of(true);

        return Optional.empty();
    }

    public List<PermissionRule> getAllowRules() {
        return List.copyOf(allowRules);
    }

    public List<PermissionRule> getDenyRules() {
        return List.copyOf(denyRules);
    }

    private void load() {
        if (persistFile == null || !Files.exists(persistFile)) return;
        try {
            var content = Files.readString(persistFile);
            var domain = JSON.fromJSON(PermissionsDomain.class, content);
            if (domain.allow != null) allowRules.addAll(domain.allow);
            if (domain.deny != null) denyRules.addAll(domain.deny);
            logger.debug("loaded {} allow / {} deny rules from {}", allowRules.size(), denyRules.size(), persistFile);
        } catch (IOException e) {
            logger.warn("failed to load permission rules from {}", persistFile, e);
        }
    }

    private void save() {
        if (persistFile == null) return;
        try {
            Files.createDirectories(persistFile.getParent());
            var domain = new PermissionsDomain();
            domain.allow = new ArrayList<>(allowRules);
            domain.deny = new ArrayList<>(denyRules);
            Files.writeString(persistFile, JSON.toJSON(domain));
            logger.debug("saved {} allow / {} deny rules to {}", allowRules.size(), denyRules.size(), persistFile);
        } catch (IOException e) {
            logger.warn("failed to save permission rules to {}", persistFile, e);
        }
    }

    public static class PermissionsDomain {
        @Property(name = "allow")
        public List<PermissionRule> allow;

        @Property(name = "deny")
        public List<PermissionRule> deny;
    }
}
