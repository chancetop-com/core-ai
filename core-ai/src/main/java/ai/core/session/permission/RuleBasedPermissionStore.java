package ai.core.session.permission;

import ai.core.session.ToolPermissionStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class RuleBasedPermissionStore implements ToolPermissionStore {
    private final List<PermissionRule> persistentRules = new CopyOnWriteArrayList<>();
    private final List<PermissionRule> sessionRules = new CopyOnWriteArrayList<>();

    @Override
    public boolean isApproved(String toolName) {
        return matchRule(toolName, Map.of())
                .map(r -> r.getLevel() == PermissionLevel.ALLOW)
                .orElse(false);
    }

    @Override
    public void approve(String toolName) {
        addRule(new PermissionRule(toolName, null, PermissionLevel.ALLOW, PermissionScope.PERSISTENT, 0));
    }

    @Override
    public Set<String> approvedTools() {
        return Stream.concat(persistentRules.stream(), sessionRules.stream())
                .filter(r -> r.getLevel() == PermissionLevel.ALLOW)
                .map(PermissionRule::getToolName)
                .collect(Collectors.toSet());
    }

    @Override
    public void addRule(PermissionRule rule) {
        removeRule(rule.getToolName(), rule.getPathPattern());
        if (rule.getScope() == PermissionScope.SESSION) {
            sessionRules.add(rule);
        } else {
            persistentRules.add(rule);
        }
    }

    @Override
    public void removeRule(String toolName, String pathPattern) {
        persistentRules.removeIf(r -> r.getToolName().equals(toolName)
                && java.util.Objects.equals(r.getPathPattern(), pathPattern));
        sessionRules.removeIf(r -> r.getToolName().equals(toolName)
                && java.util.Objects.equals(r.getPathPattern(), pathPattern));
    }

    @Override
    public List<PermissionRule> getRules() {
        var all = new ArrayList<PermissionRule>();
        all.addAll(persistentRules);
        all.addAll(sessionRules);
        return List.copyOf(all);
    }

    @Override
    public Optional<PermissionRule> matchRule(String toolName, Map<String, Object> arguments) {
        var path = PathExtractor.extractPath(toolName, arguments).orElse(null);

        return Stream.concat(sessionRules.stream(), persistentRules.stream())
                .filter(r -> r.matchesToolName(toolName))
                .filter(r -> r.matchesPath(path))
                .max(Comparator.comparingInt(PermissionRule::getPriority));
    }

    public void clearSessionRules() {
        sessionRules.clear();
    }

    public List<PermissionRule> getSessionRules() {
        return List.copyOf(sessionRules);
    }

    public List<PermissionRule> getPersistentRules() {
        return List.copyOf(persistentRules);
    }
}
