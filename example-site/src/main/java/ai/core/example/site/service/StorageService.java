package ai.core.example.site.service;

import app.azure.storage.AzureBlobClient;
import app.azure.storage.AzureBlobClientBuilder;
import core.framework.util.Strings;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * @author stephen
 */
public class StorageService {
    public String storageContainer;
    public String storageAccount;
    public String accountKey;

    public StorageService(String storageAccount, String accountKey, String storageContainer) {
        this.storageAccount = storageAccount;
        this.accountKey = accountKey;
        this.storageContainer = storageContainer;
    }

    public String url(String key) {
        return Strings.format("https://{}.blob.core.windows.net/{}/{}", storageAccount, storageContainer, key);
    }

    public String put(String path, String fileName, File file) {
        String key = objectKey(fileName, path);
        upload(key, file);
        return key;
    }

    private String objectKey(String fileName, String path) {
        String name = fileName.substring(1 + fileName.lastIndexOf(File.separator));
        if (path.endsWith(File.separator)) {
            return path + name;
        }
        return path + File.separator + name;
    }

    private void upload(String objectKey, File file) {
        AzureBlobClient client = new AzureBlobClientBuilder(storageAccount).key(accountKey).build();
        try (InputStream input = java.nio.file.Files.newInputStream(file.toPath())) {
            client.upload(storageContainer, objectKey, input.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
