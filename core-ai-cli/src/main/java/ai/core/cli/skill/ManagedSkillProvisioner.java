package ai.core.cli.skill;

import ai.core.cli.ConsoleWriter;
import ai.core.cli.auth.AuthConfig;
import ai.core.cli.hub.skill.LocalSkillScanner;
import ai.core.cli.hub.skill.SkillHubClient;
import ai.core.cli.hub.skill.SkillInstaller;
import ai.core.cli.hub.skill.SkillLocations;
import ai.core.cli.ui.AnsiTheme;
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

/**
 * Ensures managed skills (e.g. browser-use) are available on startup. When logged in,
 * skills are pulled from the core-ai-server skill hub (digest-checked, so a changed
 * server copy is re-pulled but local modifications are kept); without a server the
 * official GitHub source is the fallback.
 *
 * @author stephen
 */
public class ManagedSkillProvisioner {
    private static final Logger LOGGER = LoggerFactory.getLogger(ManagedSkillProvisioner.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Duration ARCHIVE_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_SKILL_FILE_SIZE = 512 * 1024;
    private static final String SKILL_FILE_NAME = "SKILL.md";
    private static final String WINDOWS_USAGE_MARKER = "## Windows Usage";
    private static final Path USER_SKILLS_DIR = SkillLocations.userSkillsDir();
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
            String outcome = provision(skill);
            if (outcome != null) ConsoleWriter.println(outcome);
        }
    }

    static String provision(ManagedSkill skill) {
        if (installFromServer(skill.name())) return null;
        if (isInstalled(USER_SKILLS_DIR, skill.name())) return null;
        if (installFromGithub(skill)) {
            return AnsiTheme.SUCCESS + "  \u2713" + AnsiTheme.RESET + " Installed managed skill: " + skill.name();
        }
        return AnsiTheme.MUTED + "  Failed to install managed skill: " + skill.name()
                + " (run /skill or core-ai-cli skill pull to install manually)" + AnsiTheme.RESET;
    }

    /** File-presence check kept for legacy flat and namespaced layouts (GitHub-installed skills carry no marker). */
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

    /**
     * Pulls from the server hub when the connected server hosts the skill: installs on
     * first sight, refreshes outdated copies, keeps locally modified ones, and upgrades
     * legacy marker-less installs. False when the server has no such skill or is unreachable.
     */
    private static boolean installFromServer(String name) {
        var auth = AuthConfig.load();
        if (auth == null || auth.serverUrl() == null || auth.apiKey() == null || auth.apiKey().isBlank()) return false;
        try {
            var client = new SkillHubClient(auth.serverUrl(), auth.apiKey(), TIMEOUT, ARCHIVE_TIMEOUT);
            var qualified = lookupSingle(client, name);
            if (qualified == null) return false;
            var local = findLocal(name);
            if (local != null && local.marker() != null && local.marker().isManaged()) {
                if (local.marker().digest().equals(serverDigest(client, qualified))) return true;   // up to date
                if (!local.digest().equals(local.marker().digest())) {
                    LOGGER.info("managed skill '{}' modified locally, keeping local changes", name);
                    return true;
                }
            }
            var archive = client.archive(qualified.namespace(), qualified.name());
            var dir = USER_SKILLS_DIR.resolve(qualified.namespace()).resolve(qualified.name());
            boolean force = local != null && local.marker() == null;   // legacy install, safe to replace
            var source = new ai.core.cli.hub.skill.SkillHubMarker.Marker(qualified.qualifiedName(), archive.id(),
                    archive.digest() != null ? archive.digest() : "", auth.serverUrl(), null);
            new SkillInstaller().install(dir, archive.bytes(), source, force);
            return true;
        } catch (Exception e) {
            LOGGER.warn("failed to install managed skill '{}' from server: {}", name, e.getMessage());
            return false;
        }
    }

    private static String serverDigest(SkillHubClient client, ai.core.cli.hub.skill.SkillNameResolver.QualifiedName qualified) {
        var search = client.search(qualified.name(), null, "upload", 5);
        if (search.skills != null) {
            for (var skill : search.skills) {
                if (qualified.qualifiedName().equals(skill.qualifiedName) && skill.digest != null) return skill.digest;
            }
        }
        return null;
    }

    private static ai.core.cli.hub.skill.SkillNameResolver.QualifiedName lookupSingle(SkillHubClient client, String name) {
        try {
            return new ai.core.cli.hub.skill.SkillNameResolver().resolve(client, name);
        } catch (ai.core.cli.hub.HubCliError | ai.core.cli.http.RemoteApiException e) {
            return null;   // absent or ambiguous on the server → caller falls back to GitHub
        }
    }

    private static LocalSkillScanner.LocalSkill findLocal(String name) {
        for (var local : new LocalSkillScanner().scan(USER_SKILLS_DIR)) {
            if (local.name().equals(name)) return local;
            if (local.marker() != null && local.marker().qualifiedName() != null
                    && local.marker().qualifiedName().endsWith("/" + name)) {
                return local;
            }
        }
        return null;
    }

    private static boolean installFromGithub(ManagedSkill skill) {
        String content = fetch(skill.fallbackUrl());
        if (content == null) return false;
        try {
            writeSkillFiles(USER_SKILLS_DIR.resolve(skill.name()), adaptForWindows(content, isWindows()));
            return true;
        } catch (IOException e) {
            LOGGER.warn("failed to write managed skill '{}': {}", skill.name(), e.getMessage());
            return false;
        }
    }

    private static void writeSkillFiles(Path skillDir, String content) throws IOException {
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve(SKILL_FILE_NAME), content, StandardCharsets.UTF_8);
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
