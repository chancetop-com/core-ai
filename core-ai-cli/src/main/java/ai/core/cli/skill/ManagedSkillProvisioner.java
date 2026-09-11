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

            ### One dedicated Edge instance per site

            Chrome/Edge expose the default profile to automation only after someone clicks "Allow remote debugging?" in a popup, which no automated run can do. Start a separate Edge instance whose profile exists for exactly this purpose, and attach to it with BU_CDP_URL:

            ```powershell
            $site = "myapp"        # one profile per site, so a logged-in site keeps its login
            $profile = "$env:USERPROFILE\\.core-ai\\browser-profiles\\$site"
            Start-Process msedge.exe -ArgumentList "--remote-debugging-port=9222", "--user-data-dir=$profile", "--no-first-run", "--no-default-browser-check"
            Invoke-RestMethod http://127.0.0.1:9222/json/version    # wait until this answers
            $env:BU_CDP_URL = "http://127.0.0.1:9222"               # in the shell that runs browser-use
            ```

            - Check `%USERPROFILE%\\.core-ai\\browser-profiles` first: a profile for that site already carries its login. Never park a profile in `%TEMP%`, Windows cleanup deletes it.
            - No login yet: open the site in that window, ask the user to sign in once, then continue. Never type credentials yourself, and never answer a login-walled question from search results or another site instead.
            - The daemon keeps one browser connection: if it was attached elsewhere before this Edge instance started, run `browser-use --reload` once. A second site at the same time needs another port (9223) and its own `$env:BU_NAME`.
            - Close an instance through its command line, never by killing every Edge process:

            ```powershell
            Get-CimInstance Win32_Process -Filter "Name='msedge.exe'" |
              Where-Object { $_.CommandLine -like '*--user-data-dir=*browser-profiles*' -and $_.CommandLine -notlike '*--type=*' } |
              ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
            ```

            ### Every call is a fresh script

            Each `browser-use` call runs in a new process: tabs, page state and cookies stay in the daemon, Python variables do not. Do related steps in one call, print what the next decision needs, and keep shared state on the page or in files.

            ### Helpers: reuse before writing your own

            Pre-imported: `cdp, goto_url, page_info, list_tabs, current_tab, switch_tab, new_tab, close_tab, activate_tab, ensure_real_tab, iframe_target, click_at_xy, type_text, fill_input, press_key, dispatch_key, scroll, capture_screenshot, wait, wait_for_load, wait_for_element, wait_for_network_idle, js, upload_file, http_get, drain_events, start_recording, stop_recording, recordings`.

            Also loaded from `$BH_AGENT_WORKSPACE\\agent_helpers.py`, wrapping the Page Workflow accessibility-tree loop into one call each: `ax_list(name=None, role=None)`, `ax_find(name, role=None, index=0, exact=False)`, `click_ax(name, role=None)` (find, scroll into view, click), `box_center(id)`, `dump()` (URL, title, visible text), `snap()` (screenshot path), `settle(sec)`, `console_errors()`, `api_log_errors()` (non-2xx fetch/XHR), `json_val(expr)`. Put reusable helpers in that file instead of re-writing them inline, it is loaded again on the next call.

            ### Verify cheaply

            - Prefer the accessibility tree over pixels, and take a screenshot (`snap()`) only when the visual result is the point. Read it with `read_file`.
            - After an interaction take exactly one cheap state check - `dump()`, a title/URL change, `console_errors()`, an API result - not a dump plus a screenshot plus a `js` probe.
            - One authoritative signal settles the question. Do not re-verify it through badges, a second page, or another snapshot.

            ### When it does not work

            - Element not found: re-read the AX tree, the page changed.
            - `NameError`, `AttributeError` or bad arguments are your script's bug: fix the call, do not retry it.
            - The user asked about UI or page behaviour: drive the page. Do not answer from source code, and do not swap in API calls for the UI they asked to see.
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
