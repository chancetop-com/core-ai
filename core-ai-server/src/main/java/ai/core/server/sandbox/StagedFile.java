package ai.core.server.sandbox;

/** A workflow input file to stage into a consumer sandbox at a deterministic path before it starts. */
public record StagedFile(String fileId, String fileName, String targetPath) {
}
