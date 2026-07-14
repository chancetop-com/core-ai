package ai.core.server.blob;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AzureBlobSasServiceTest {
    @Test
    void deleteSasCarriesDeletePermissionOnBlobResource() {
        var key = Base64.getEncoder().encodeToString("test-account-key".getBytes(StandardCharsets.UTF_8));
        var service = new AzureBlobSasService("testaccount", key);

        var result = service.generateDeleteBlobSas("snapshots", "u1/s1/snap.tar.gz", 5);

        assertTrue(result.uploadUrl().contains("sp=d"), "SAS must carry delete permission");
        assertTrue(result.uploadUrl().contains("sr=b"), "SAS must be blob-scoped");
        assertTrue(result.uploadUrl().contains("/snapshots/u1/s1/snap.tar.gz?"));
    }
}
