package ai.core.server.sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * @author stephen
 */
public interface TokenResolver {
    String resolve();

    static TokenResolver fixed(String token) {
        return () -> token;
    }

    static TokenResolver inCluster() {
        return () -> {
            try {
                return Files.readString(Path.of("/var/run/secrets/kubernetes.io/serviceaccount/token")).trim();
            } catch (IOException e) {
                throw new RuntimeException("Failed to read in-cluster token", e);
            }
        };
    }
}

