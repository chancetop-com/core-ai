package ai.core.lsp.service;

import org.eclipse.lsp4j.DeclarationParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.ImplementationParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.ReferenceContext;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TypeDefinitionParams;
import org.eclipse.lsp4j.WorkspaceDiagnosticParams;
import org.eclipse.lsp4j.WorkspaceSymbolParams;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

import java.io.File;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/**
 * @author stephen
 */
public abstract class LanguageServerService {
    private final LanguageServerConfig config;
    private final LanguageServerManager manager;

    protected LanguageServerService(LanguageServerConfig config, LanguageServerManager manager) {
        this.config = config;
        this.manager = manager;
    }

    public String getWorkspaceDiagnostic(String workspace) {
        var params = new WorkspaceDiagnosticParams();
        var rst = getWorkspaceService(workspace).diagnostic(params);
        try {
            var diagnostic = rst.get();
            return diagnostic.getItems().stream()
                    .map(v -> "WorkspaceDocumentDiagnosticReport: " + v.getWorkspaceFullDocumentDiagnosticReport())
                    .collect(Collectors.joining("\n"));
        } catch (InterruptedException | ExecutionException e) {
            return "Failed to get diagnostic: " + e.getMessage();
        }
    }

    public String getWorkspaceSymbol(String workspace, String query) {
        var params = new WorkspaceSymbolParams(query);
        var rst = getWorkspaceService(workspace).symbol(params);
        try {
            var symbols = rst.get();
            return symbols.isLeft()
                    ?
                    symbols.getLeft()
                            .stream()
                            .map(v -> "SymbolInformation: " + v)
                            .collect(Collectors.joining("\n"))
                    :
                    symbols.getRight()
                            .stream()
                            .map(v -> "WorkspaceSymbol: " + v)
                            .collect(Collectors.joining("\n"));
        } catch (InterruptedException | ExecutionException e) {
            return "Failed to get symbols: " + e.getMessage();
        }
    }

    public String getDocumentSymbols(String workspace, String path) {
        var params = new DocumentSymbolParams(getTextDocumentIdentifier(workspace, path));
        var rst = getTextDocumentService(workspace).documentSymbol(params);
        try {
            var symbols = rst.get();
            return symbols.stream()
                    .map(v -> v.isLeft() ? "SymbolInformation: " + v.getLeft() : "DocumentSymbol: " + v.getRight())
                    .collect(Collectors.joining("\n"));
        } catch (InterruptedException | ExecutionException e) {
            return "Failed to get symbols: " + e.getMessage();
        }
    }

    public String getDeclaration(String workspace, String path, int line, int character) {
        var params = new DeclarationParams(getTextDocumentIdentifier(workspace, path), new Position(line, character));
        var rst = getTextDocumentService(workspace).declaration(params);
        try {
            var declaration = rst.get();
            return declaration.isLeft()
                    ?
                    declaration.getLeft()
                            .stream()
                            .map(v -> "Location: " + v.getUri() + v.getRange())
                            .collect(Collectors.joining("\n"))
                    :
                    declaration.getRight()
                            .stream()
                            .map(v -> "LocationLink: " + v.getTargetUri() + v.getTargetRange())
                            .collect(Collectors.joining("\n"));
        } catch (InterruptedException | ExecutionException e) {
            return "Failed to get declarations: " + e.getMessage();
        }
    }

    public String getImplementation(String workspace, String path, int line, int character) {
        var params = new ImplementationParams(getTextDocumentIdentifier(workspace, path), new Position(line, character));
        var rst = getTextDocumentService(workspace).implementation(params);
        try {
            var implementation = rst.get();
            return implementation.isLeft()
                    ?
                    implementation.getLeft()
                            .stream()
                            .map(v -> "Location: " + v.getUri() + v.getRange())
                            .collect(Collectors.joining("\n"))
                    :
                    implementation.getRight()
                            .stream()
                            .map(v -> "LocationLink: " + v.getTargetUri() + v.getTargetRange())
                            .collect(Collectors.joining("\n"));
        } catch (InterruptedException | ExecutionException e) {
            return "Failed to get implementation: " + e.getMessage();
        }
    }

    public String getTypeDefinitions(String workspace, String path, int line, int character) {
        var params = new TypeDefinitionParams(getTextDocumentIdentifier(workspace, path), new Position(line, character));
        var rst = getTextDocumentService(workspace).typeDefinition(params);
        try {
            var typeDefinitions = rst.get();
            return typeDefinitions.isLeft()
                    ?
                    typeDefinitions.getLeft()
                            .stream()
                            .map(v -> "Location: " + v.getUri() + v.getRange())
                            .collect(Collectors.joining("\n"))
                    :
                    typeDefinitions.getRight()
                            .stream()
                            .map(v -> "LocationLink: " + v.getTargetUri() + v.getTargetRange())
                            .collect(Collectors.joining("\n"));
        } catch (InterruptedException | ExecutionException e) {
            return "Failed to get type definitions: " + e.getMessage();
        }
    }

    public String getDefinition(String workspace, String path, int line, int character) {
        var params = new DefinitionParams(getTextDocumentIdentifier(workspace, path), new Position(line, character));
        var rst = getTextDocumentService(workspace).definition(params);
        try {
            var definitions = rst.get();
            return definitions.isLeft()
                    ?
                    definitions.getLeft()
                            .stream()
                            .map(location -> "Location: " + location.getUri() + " " + location.getRange())
                            .collect(Collectors.joining("\n"))
                    :
                    definitions.getRight()
                            .stream()
                            .map(locationLink -> "LocationLink: " + locationLink.getTargetUri() + " " + locationLink.getTargetRange())
                            .collect(Collectors.joining("\n"));
        } catch (InterruptedException | ExecutionException e) {
            return "Failed to get definitions: " + e.getMessage();
        }
    }

    public String getReferences(String workspace, String path, int line, int character) {
        var params = new ReferenceParams(getTextDocumentIdentifier(workspace, path), new Position(line, character), new ReferenceContext(true));
        var rst = getTextDocumentService(workspace).references(params);
        try {
            var references = rst.get();
            if (references == null || references.isEmpty()) {
                return "No references found.";
            }
            return references
                    .stream()
                    .map(location -> "Location: " + location.getUri() + " " + location.getRange())
                    .collect(Collectors.joining("\n"));
        } catch (InterruptedException | ExecutionException e) {
            return "Failed to get references: " + e.getMessage();
        }
    }

    private TextDocumentIdentifier getTextDocumentIdentifier(String workspace, String path) {
        validate(workspace, path);
        var uri = new File(path).toURI();
        return new TextDocumentIdentifier(uri.toASCIIString());
    }

    public TextDocumentService getTextDocumentService(String workspace) {
        var server = manager.getServer(config, workspace);
        return server.getTextDocumentService();
    }

    public WorkspaceService getWorkspaceService(String workspace) {
        var server = manager.getServer(config, workspace);
        return server.getWorkspaceService();
    }

    private void validate(String workspace, String path) {
        var workspaceDir = new File(workspace);
        if (!workspaceDir.exists() || !workspaceDir.isDirectory()) {
            throw new IllegalArgumentException("Workspace does not exist or is not a directory: " + workspace);
        }

        var file = new File(path);
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("File does not exist or is not a valid file: " + path);
        }
    }
}
