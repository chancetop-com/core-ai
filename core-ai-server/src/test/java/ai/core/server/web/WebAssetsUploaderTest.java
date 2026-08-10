package ai.core.server.web;

import ai.core.server.blob.ObjectStorageService;
import ai.core.server.blob.ObjectStorageServiceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebAssetsUploaderTest {
    @TempDir
    Path tempDir;

    private WebAssetsUploader uploader;
    private ObjectStorageServiceResolver resolver;
    private ObjectStorageService storage;
    private StaticFileController controller;

    @BeforeEach
    void setUp() {
        uploader = new WebAssetsUploader();
        resolver = mock(ObjectStorageServiceResolver.class);
        storage = mock(ObjectStorageService.class);
        uploader.storageResolver = resolver;
        controller = new StaticFileController(tempDir);
        when(resolver.resolve()).thenReturn(storage);
        when(resolver.webAssetsPublicBaseUrl()).thenReturn("https://assets.example.com");
        when(resolver.multimodalContainer()).thenReturn("static");
        when(resolver.webAssetsRedirectBase()).thenReturn("https://assets.example.com/static/web-assets");
    }

    @Test
    void keepsLocalServingWhenStorageNotConfigured() throws IOException {
        when(resolver.resolve()).thenReturn(null);
        createAsset("app.js");

        uploader.upload(tempDir, controller);

        assertNull(controller.webAssetsRedirectBase);
        verify(storage, never()).exists(anyString(), anyString());
    }

    @Test
    void keepsLocalServingWhenPublicBaseUrlMissing() throws IOException {
        when(resolver.webAssetsPublicBaseUrl()).thenReturn(null);
        createAsset("app.js");

        uploader.upload(tempDir, controller);

        assertNull(controller.webAssetsRedirectBase);
    }

    @Test
    void uploadsMissingFilesOnly() throws Exception {
        createAsset("app.js");
        createAsset("app.css");
        when(storage.exists("static", "web-assets/assets/app.js")).thenReturn(Boolean.TRUE);
        when(storage.exists("static", "web-assets/assets/app.css")).thenReturn(Boolean.FALSE);
        mockProbe(200);

        uploader.upload(tempDir, controller);

        verify(storage, never()).uploadObject(eq("static"), eq("web-assets/assets/app.js"), any(Path.class), anyString());
        verify(storage).uploadObject(eq("static"), eq("web-assets/assets/app.css"), any(Path.class), eq("text/css"));
        assertEquals("https://assets.example.com/static/web-assets", controller.webAssetsRedirectBase);
    }

    @Test
    void keepsLocalServingWhenPublicProbeFails() throws Exception {
        createAsset("app.js");
        when(storage.exists("static", "web-assets/assets/app.js")).thenReturn(Boolean.FALSE);
        mockProbe(403);

        uploader.upload(tempDir, controller);

        assertNull(controller.webAssetsRedirectBase);
        verify(storage).uploadObject(eq("static"), eq("web-assets/assets/app.js"), any(Path.class), eq("application/javascript"));
    }

    @Test
    void keepsLocalServingWhenUploadThrows() throws IOException {
        createAsset("app.js");
        when(storage.exists("static", "web-assets/assets/app.js")).thenThrow(new RuntimeException("storage down"));

        uploader.upload(tempDir, controller);

        assertNull(controller.webAssetsRedirectBase);
    }

    private void createAsset(String name) throws IOException {
        var file = tempDir.resolve("assets").resolve(name);
        Files.createDirectories(Objects.requireNonNull(file.getParent()));
        Files.writeString(file, "content");
    }

    @SuppressWarnings("unchecked")
    private void mockProbe(int statusCode) throws IOException, InterruptedException {
        var client = mock(HttpClient.class);
        var response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(statusCode);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        uploader.httpClient = client;
    }
}
