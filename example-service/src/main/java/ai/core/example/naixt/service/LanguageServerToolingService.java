package ai.core.example.naixt.service;

import ai.core.lsp.service.LanguageServerService;
import ai.core.tool.function.annotation.CoreAiMethod;
import ai.core.tool.function.annotation.CoreAiParameter;
import core.framework.inject.Inject;

/**
 * @author stephen
 */
public class LanguageServerToolingService {
    @Inject
    LanguageServerService languageServerService;

    @CoreAiMethod(name = "Get workspace diagnostic", description = "Get workspace diagnostic")
    public String getWorkspaceDiagnostic(@CoreAiParameter(
            name = "workspace",
            description = "the workspace path",
            required = true) String workspace) {
        return languageServerService.getWorkspaceDiagnostic(workspace);
    }

    @CoreAiMethod(name = "Get workspace symbols", description = "Get symbols in the workspace")
    public String getWorkspaceSymbols(@CoreAiParameter(
                                              name = "workspace",
                                              description = "the workspace path",
                                              required = true) String workspace,
                                      @CoreAiParameter(
                                              name = "query",
                                              description = "the query to search for symbols",
                                              required = true) String query) {
        return languageServerService.getWorkspaceSymbol(workspace, query);
    }

    @CoreAiMethod(name = "Get document symbols", description = "Get symbols in a document")
    public String getDocumentSymbols(@CoreAiParameter(
                                             name = "workspace",
                                             description = "the workspace path",
                                             required = true) String workspace,
                                     @CoreAiParameter(
                                             name = "path",
                                             description = "the path to the document",
                                             required = true) String path) {
        return languageServerService.getDocumentSymbols(workspace, path);
    }

    @CoreAiMethod(name = "Get declaration", description = "Get the declaration of a symbol")
    public String getDeclaration(@CoreAiParameter(
                                         name = "workspace",
                                         description = "the workspace path",
                                         required = true) String workspace,
                                 @CoreAiParameter(
                                         name = "path",
                                         description = "the path to the document",
                                         required = true) String path,
                                 @CoreAiParameter(
                                         name = "line",
                                         description = "the line number",
                                         required = true) int line,
                                 @CoreAiParameter(
                                         name = "character",
                                         description = "the character position",
                                         required = true) int character) {
        return languageServerService.getDeclaration(workspace, path, line, character);
    }

    @CoreAiMethod(name = "Get implementation", description = "Get the implementation of a symbol")
    public String getImplementation(@CoreAiParameter(
                                            name = "workspace",
                                            description = "the workspace path",
                                            required = true) String workspace,
                                    @CoreAiParameter(
                                            name = "path",
                                            description = "the path to the document",
                                            required = true) String path,
                                    @CoreAiParameter(
                                            name = "line",
                                            description = "the line number",
                                            required = true) int line,
                                    @CoreAiParameter(
                                            name = "character",
                                            description = "the character position",
                                            required = true) int character) {
        return languageServerService.getImplementation(workspace, path, line, character);
    }

    @CoreAiMethod(name = "Get type definitions", description = "Get the type definitions of a symbol")
    public String getTypeDefinitions(@CoreAiParameter(
                                             name = "workspace",
                                             description = "the workspace path",
                                             required = true) String workspace,
                                     @CoreAiParameter(
                                             name = "path",
                                             description = "the path to the document",
                                             required = true) String path,
                                     @CoreAiParameter(
                                             name = "line",
                                             description = "the line number",
                                             required = true) int line,
                                     @CoreAiParameter(
                                             name = "character",
                                             description = "the character position",
                                             required = true) int character) {
        return languageServerService.getTypeDefinitions(workspace, path, line, character);
    }

    @CoreAiMethod(name = "Get definition", description = "Get the definition of a symbol")
    public String getDefinition(@CoreAiParameter(
                                        name = "workspace",
                                        description = "the workspace path",
                                        required = true) String workspace,
                                @CoreAiParameter(
                                        name = "path",
                                        description = "the path to the document",
                                        required = true) String path,
                                @CoreAiParameter(
                                        name = "line",
                                        description = "the line number",
                                        required = true) int line,
                                @CoreAiParameter(
                                        name = "character",
                                        description = "the character position",
                                        required = true) int character) {
        return languageServerService.getDefinition(workspace, path, line, character);
    }

    @CoreAiMethod(name = "Get references", description = "Get the references of a symbol")
    public String getReferences(@CoreAiParameter(
                                        name = "workspace",
                                        description = "the workspace path",
                                        required = true) String workspace,
                                @CoreAiParameter(
                                        name = "path",
                                        description = "the path to the document",
                                        required = true) String path,
                                @CoreAiParameter(
                                        name = "line",
                                        description = "the line number",
                                        required = true) int line,
                                @CoreAiParameter(
                                        name = "character",
                                        description = "the character position",
                                        required = true) int character) {
        return languageServerService.getReferences(workspace, path, line, character);
    }

    @CoreAiMethod(name = "Rename symbol", description = "Rename a symbol in the document")
    public boolean renameSymbol(@CoreAiParameter(
                                        name = "workspace",
                                        description = "the workspace path",
                                        required = true) String workspace,
                                @CoreAiParameter(
                                        name = "path",
                                        description = "the path to the document",
                                        required = true) String path,
                                @CoreAiParameter(
                                        name = "line",
                                        description = "the line number",
                                        required = true) int line,
                                @CoreAiParameter(
                                        name = "character",
                                        description = "the character position",
                                        required = true) int character,
                                @CoreAiParameter(
                                        name = "newName",
                                        description = "the new name for the symbol",
                                        required = true) String newName) {
        return languageServerService.renameSymbol(workspace, path, line, character, newName);
    }
}