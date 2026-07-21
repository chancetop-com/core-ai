package ai.core.utils;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class SystemUtilTest {
    private static void awaitRelease(CountDownLatch releaseResponse) {
        try {
            releaseResponse.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void downloadHttpTimesOutWhenResponseBodyStalls(@TempDir Path dir) throws Exception {
        var releaseResponse = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/slow", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().flush();
                awaitRelease(releaseResponse);
            }
        });
        server.start();

        try {
            var url = "http://127.0.0.1:" + server.getAddress().getPort() + "/slow";
            var target = dir.resolve("download.bin").toFile();
            assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                    assertThrows(SocketTimeoutException.class, () ->
                            SystemUtil.downloadHttp(url, target, 0, 250, 250)));
        } finally {
            releaseResponse.countDown();
            server.stop(0);
        }
    }
}
