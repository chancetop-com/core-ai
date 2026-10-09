package ai.core.cli.appserver;

import ai.core.session.ToolPermissionStore;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * "Workspace auto" permission tier: file writes whose path resolves inside the workspace are approved
 * without prompting. Explicit allow/deny rules keep precedence, commands and out-of-workspace paths
 * still prompt. Containment goes through {@link CanonicalPath}, so {@code ..} and symlinks cannot
 * escape the workspace.
 *
 * @author stephen
 */
public class WorkspaceAutoPermissionStore implements ToolPermissionStore {
    private static final Set<String> WRITE_TOOLS = Set.of("write_file", "edit_file", "hash_edit_file");

    private final ToolPermissionStore delegate;
    private final Path workspace;

    public WorkspaceAutoPermissionStore(ToolPermissionStore delegate, Path workspace) {
        this.delegate = delegate;
        this.workspace = CanonicalPath.ofOrNormalized(workspace);
    }

    @Override
    public void allow(String pattern) {
        delegate.allow(pattern);
    }

    @Override
    public void deny(String pattern) {
        delegate.deny(pattern);
    }

    @Override
    public Optional<Boolean> checkPermission(String toolName, Map<String, Object> arguments) {
        var delegated = delegate.checkPermission(toolName, arguments);
        if (delegated.isPresent()) {
            return delegated;
        }
        if (WRITE_TOOLS.contains(toolName) && withinWorkspace(arguments)) {
            return Optional.of(Boolean.TRUE);
        }
        return Optional.empty();
    }

    private boolean withinWorkspace(Map<String, Object> arguments) {
        var raw = pathArgument(arguments);
        if (raw == null) {
            return false;
        }
        try {
            var path = Path.of(raw);
            var resolved = path.isAbsolute() ? path : workspace.resolve(path);
            var canonical = CanonicalPath.of(resolved.normalize());
            return canonical != null && canonical.startsWith(workspace);
        } catch (InvalidPathException e) {
            return false;
        }
    }

    private String pathArgument(Map<String, Object> arguments) {
        if (arguments == null) {
            return null;
        }
        var value = arguments.get("file_path");
        if (value == null) {
            value = arguments.get("path");
        }
        return value instanceof String text && !text.isBlank() ? text : null;
    }
}
