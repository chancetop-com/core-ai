package ai.core.cli.skill;

import ai.core.cli.ConsoleWriter;
import ai.core.cli.auth.AuthConfig;
import ai.core.cli.remote.RemoteApiClient;
import ai.core.cli.ui.AnsiTheme;
import ai.core.utils.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ensures managed skills (e.g. browser-use) are available on startup.
 * Downloads from the connected core-ai-server when logged in, otherwise falls back to the official source.
 *
 * @author stephen
 */
public class ManagedSkillProvisioner {
    private static final Logger LOGGER = LoggerFactory.getLogger(ManagedSkillProvisioner.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_SKILL_FILE_SIZE = 512 * 1024;
    private static final String SKILL_FILE_NAME = "SKILL.md";
    private static final String WINDOWS_USAGE_MARKER = "## Windows Usage";
    private static final Path USER_SKILLS_DIR = Path.of(System.getProperty("user.home"), ".core-ai", "skills");
    private static final String WINDOWS_USAGE = """
            ## Windows Usage

            On Windows the shell is PowerShell, so bash heredocs (browser-use <<'PY') do not work. Pipe the Python code instead:

            ```powershell
            @'
            print(page_info())
            '@ | browser-use
            ```

            For longer scripts, write them to a file and pipe the file:

            ```powershell
            Set-Content -Path bu.py -Value "print(page_info())" -Encoding UTF8
            Get-Content bu.py -Raw | browser-use
            ```
            """;

    private static final List<ManagedSkill> MANAGED_SKILLS = List.of(
            new ManagedSkill("browser-use",
                    "https://raw.githubusercontent.com/browser-use/browser-use/main/skills/browser-use/SKILL.md")
    );

    public static void provision() {
        for (var skill : MANAGED_SKILLS) {
            if (isInstalled(USER_SKILLS_DIR, skill.name())) continue;
            boolean installed = installFromServer(skill.name()) || installFromGithub(skill);
            if (installed) {
                ConsoleWriter.println(AnsiTheme.SUCCESS + "  \u2713" + AnsiTheme.RESET + " Installed managed skill: " + skill.name());
            } else {
                ConsoleWriter.println(AnsiTheme.MUTED + "  Failed to install managed skill: " + skill.name() + " (run /skill to install manually)" + AnsiTheme.RESET);
            }
        }
    }

    static boolean isInstalled(Path skillsDir, String name) {
        if (Files.isRegularFile(skillsDir.resolve(name).resolve(SKILL_FILE_NAME))) return true;
        try (var stream = Files.list(skillsDir)) {
            return stream.filter(Files::isDirectory)
                    .anyMatch(dir -> Files.isRegularFile(dir.resolve(name).resolve(SKILL_FILE_NAME)));
        } catch (IOException e) {
            LOGGER.debug("failed to scan skills directory: {}", e.getMessage());
            return false;
        }
    }

    static String adaptForWindows(String content, boolean windows) {
        if (!windows || content.contains(WINDOWS_USAGE_MARKER)) return content;
        return content + "\n\n" + WINDOWS_USAGE;
    }

    private static boolean installFromServer(String name) {
        var auth = AuthConfig.load();
        if (auth == null || auth.apiKey() == null || auth.apiKey().isBlank()) return false;
        try {
            var api = new RemoteApiClient(auth.serverUrl(), auth.apiKey(), TIMEOUT);
            var listJson = api.get("/api/skills");
            if (listJson == null) return false;
            String matchedId = findSkillId(listJson, name);
            if (matchedId == null) return false;
            var downloadJson = api.get("/api/skills/" + matchedId + "/download");
            if (downloadJson == null) return false;
            return writeServerSkill(downloadJson, name);
        } catch (Exception e) {
            LOGGER.warn("failed to install managed skill '{}' from server: {}", name, e.getMessage());
            return false;
        }
    }

    private static boolean installFromGithub(ManagedSkill skill) {
        String content = fetch(skill.fallbackUrl());
        if (content == null) return false;
        try {
            writeSkillFiles(USER_SKILLS_DIR.resolve(skill.name()), adaptForWindows(content, isWindows()), null);
            return true;
        } catch (IOException e) {
            LOGGER.warn("failed to write managed skill '{}': {}", skill.name(), e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static String findSkillId(String listJson, String name) {
        Map<String, Object> response = JsonUtil.fromJson(Map.class, listJson);
        var skills = (List<Map<String, Object>>) response.get("skills");
        if (skills == null) return null;
        for (var skill : skills) {
            if (name.equals(skill.get("name")) || name.equals(skill.get("qualified_name"))) {
                return (String) skill.get("id");
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static boolean writeServerSkill(String downloadJson, String name) throws IOException {
        Map<String, Object> data = JsonUtil.fromJson(Map.class, downloadJson);
        String content = (String) data.get("content");
        if (content == null || content.isBlank()) return false;
        String namespace = (String) data.get("namespace");
        var resources = (List<Map<String, String>>) data.get("resources");
        Path skillDir = namespace != null && !namespace.isBlank()
                ? USER_SKILLS_DIR.resolve(namespace).resolve(name)
                : USER_SKILLS_DIR.resolve(name);
        writeSkillFiles(skillDir, adaptForWindows(content, isWindows()), resources);
        return true;
    }

    private static void writeSkillFiles(Path skillDir, String content, List<Map<String, String>> resources) throws IOException {
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve(SKILL_FILE_NAME), content, StandardCharsets.UTF_8);
        if (resources == null) return;
        for (var resource : resources) {
            var path = resource.get("path");
            var resourceContent = resource.get("content");
            if (path == null || resourceContent == null) continue;
            Path target = skillDir.resolve(path).normalize();
            if (!target.startsWith(skillDir)) {
                LOGGER.warn("skip resource escaping skill directory: {}", path);
                continue;
            }
            Path parent = target.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(target, resourceContent, StandardCharsets.UTF_8);
        }
    }

    private static String fetch(String url) {
        try {
            var client = HttpClient.newBuilder()
                    .connectTimeout(TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            var request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("User-Agent", "core-ai-cli")
                    .GET()
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOGGER.warn("download failed with status {}: {}", response.statusCode(), url);
                return null;
            }
            String body = response.body();
            if (body.length() > MAX_SKILL_FILE_SIZE) {
                LOGGER.warn("download too large, skipped: {}", url);
                return null;
            }
            return body;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("download interrupted: {}", e.getMessage());
            return null;
        } catch (IOException | IllegalArgumentException e) {
            LOGGER.warn("download failed: {}", e.getMessage());
            return null;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }

    record ManagedSkill(String name, String fallbackUrl) {
    }
}
