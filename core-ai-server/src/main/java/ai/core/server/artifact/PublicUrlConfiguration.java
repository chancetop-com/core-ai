package ai.core.server.artifact;

/**
 * @author stephen
 */
public record PublicUrlConfiguration(String value) {
    public PublicUrlConfiguration(String value) {
        this.value = normalize(value);
    }

    public String fileDownloadUrl(String fileId) {
        return value + "/api/files/" + fileId + "/content";
    }

    public String sharedArtifactDownloadUrl(String shareToken) {
        return value + "/api/public/artifacts/" + shareToken + "/content";
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) return "";
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
