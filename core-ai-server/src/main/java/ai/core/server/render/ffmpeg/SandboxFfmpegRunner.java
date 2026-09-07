package ai.core.server.render.ffmpeg;

import ai.core.sandbox.Sandbox;
import ai.core.sandbox.SandboxConfig;
import ai.core.server.file.FileService;
import ai.core.server.sandbox.SandboxService;
import ai.core.tool.tools.ShellCommandTool;
import ai.core.utils.JsonUtil;
import core.framework.http.HTTPClient;
import core.framework.http.HTTPMethod;
import core.framework.http.HTTPRequest;
import core.framework.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs a declarative ffmpeg plan inside a sandbox from the shared pool: pull the inputs, write the
 * generated files, run each step, persist the declared outputs. The API server process never runs
 * ffmpeg itself — it is CPU-heavy, of unbounded duration, and a third-party binary.
 * <p>
 * The sandbox is job-scoped: acquired under the caller's job key and released in a finally, so a long
 * render is never reclaimed by session-idle cleanup. Callers supply the ffmpeg major they expect,
 * because an image whose ffmpeg drifted would produce differently-encoded products under a cache key
 * that claims otherwise — the runtime reports what it actually ships on /health and a mismatch is
 * refused before anything runs.
 *
 * @author stephen
 */
public class SandboxFfmpegRunner implements FfmpegRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(SandboxFfmpegRunner.class);

    private static String readText(java.nio.file.Path path) {
        try {
            return java.nio.file.Files.readString(path, StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("cannot read inline product " + path, e);
        }
    }

    @Inject
    SandboxService sandboxService;
    @Inject
    FileService fileService;

    private final HTTPClient httpClient = HTTPClient.builder()
        .connectTimeout(Duration.ofSeconds(5))
        .timeout(Duration.ofSeconds(15))
        .trustAll()
        .build();

    @Override
    public Map<String, Product> run(Plan plan) {
        var sandboxKey = plan.jobKey();
        var workDir = "/tmp/" + sandboxKey;
        try {
            var sandbox = acquire(plan, sandboxKey);
            requireFfmpegMajor(sandbox, plan.expectedFfmpegMajor());
            return execute(sandbox, plan, workDir);
        } finally {
            sandboxService.releaseSandbox(sandboxKey);
        }
    }

    /** The sandbox TTL is only bumped per tool call, so a long ffmpeg step needs the lease heartbeat to renew it. */
    @Override
    public void renew(String jobKey) {
        sandboxService.renewSandbox(jobKey);
    }

    /**
     * A session sandbox is lazy: the container is only acquired on first use, and until then
     * {@code ip()} is null and {@code port()} is 0. The health probe reads both, so acquisition has
     * to be forced here rather than left to the first command.
     */
    Sandbox acquire(Plan plan, String sandboxKey) {
        var sandbox = sandboxService.createSandbox(sandboxConfig(plan), sandboxKey, plan.userId());
        if (sandbox == null) throw new IllegalStateException("no sandbox provider configured — ffmpeg plans cannot run");
        sandboxService.ensureSandboxReady(sandboxKey);
        return sandbox;
    }

    Map<String, Product> execute(Sandbox sandbox, Plan plan, String workDir) {
        bash(sandbox, "mkdir -p " + quote(workDir), null, plan.stepTimeoutMs());
        for (var download : plan.downloads()) {
            // -f so an HTML error page never lands where a video is expected
            bash(sandbox, "curl -fsSL " + quote(download.url()) + " -o " + quote(FfmpegRunner.safeName(download.fileName())), workDir, plan.stepTimeoutMs());
        }
        for (var write : plan.writes()) {
            sandbox.uploadFile(workDir + "/" + FfmpegRunner.safeName(write.fileName()), write.content().getBytes(StandardCharsets.UTF_8));
        }
        for (var step : plan.steps()) {
            bash(sandbox, ffmpegCommand(step), workDir, plan.stepTimeoutMs());
        }
        var products = new LinkedHashMap<String, Product>();
        for (var output : plan.outputs()) {
            var file = sandbox.downloadFile(workDir + "/" + FfmpegRunner.safeName(output.fileName()));
            if (output.inline()) {
                products.put(output.kind(), new Product(null, readText(file.path())));
            } else {
                products.put(output.kind(), Product.of(fileService.uploadIfAbsent(plan.userId(), output.fileName(), output.contentType(), file.path())));
            }
        }
        return products;
    }

    private void requireFfmpegMajor(Sandbox sandbox, int expected) {
        var response = httpClient.execute(new HTTPRequest(HTTPMethod.GET, "http://" + sandbox.ip() + ":" + sandbox.port() + "/health"));
        if (response.statusCode != 200) throw new IllegalStateException("sandbox health check failed: HTTP " + response.statusCode);
        requireFfmpegMajor(response.text(), expected);
    }

    void requireFfmpegMajor(String healthJson, int expected) {
        var reported = JsonUtil.toMap(healthJson).get("ffmpeg_major") instanceof Number number ? number.intValue() : 0;
        if (reported == expected) return;
        throw new IllegalStateException("FFMPEG_VERSION_MISMATCH: this pipeline files products under a cache key pinned to ffmpeg major "
            + expected + " but the sandbox image reports " + (reported == 0 ? "none" : reported)
            + " — rebuild the runtime image with the pinned ffmpeg, or bump the expected major together with the image");
    }

    private void bash(Sandbox sandbox, String command, String workspace, long timeoutMs) {
        var arguments = new LinkedHashMap<String, Object>();
        arguments.put("command", command);
        if (workspace != null) arguments.put("workspace", workspace);
        arguments.put("timeout", timeoutMs);
        var result = sandbox.execute(ShellCommandTool.TOOL_NAME, JsonUtil.toJson(arguments), null);
        if (result.isFailed() || !result.isTerminal())
            throw new IllegalStateException("ffmpeg plan step failed (" + result.getStatus() + "): " + command + " -> " + result.getResult());
        LOGGER.debug("ffmpeg plan step done: {}", command);
    }

    String ffmpegCommand(List<String> step) {
        var command = new StringBuilder(FfmpegRunner.binaryFor(step));
        for (var argument : FfmpegRunner.arguments(step)) {
            command.append(' ').append(quote(argument));
        }
        return command.toString();
    }

    /** Single-quote shell quoting: nothing in a plan (urls, filter graphs) may reach the shell as syntax. */
    String quote(String value) {
        return '\'' + value.replace("'", "'\\''") + '\'';
    }

    private SandboxConfig sandboxConfig(Plan plan) {
        var config = SandboxService.createDefaultConfig();
        // inputs are pulled straight from object storage by the sandbox
        config.networkEnabled = Boolean.TRUE;
        config.timeoutSeconds = plan.sandboxTtlSeconds();
        return config;
    }
}
