package ai.core.lsp.service.nbls;

import ai.core.lsp.service.LanguageServerConfig;
import ai.core.lsp.service.LanguageServerManager;
import core.framework.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * @author stephen
 */
public class NBLanguageServerManager extends LanguageServerManager {
    private final Logger logger = LoggerFactory.getLogger(NBLanguageServerManager.class);

    private Socket socket;

    @Override
    public ProcessBuilder setupProcessBuilder(LanguageServerConfig config, String workspace) {
        if (getProcess() != null) {
            throw new IllegalStateException("Language Server is already running!");
        }

        var exec = getExecPath(config.lsHome());

        var builder = new ProcessBuilder(
                exec,
                "--jdkhome", config.jdkHome(),
                "--userdir", config.workspace(),
                "-J-Dproject.limitScanRoot=" + workspace,
                "-J-XX:PerfMaxStringConstLength=10240",
                "-J--enable-native-access=ALL-UNNAMED",
                "-J-DTopSecurityManager.disable=true",
                "--laf", "com.formdev.flatlaf.FlatDarkLaf",
                "--modules",
                "--list",
                "--locale", "en",
                "--start-java-language-server=listen:0"
//                "-J-Dnetbeans.logger.console=true"
        );

        builder.redirectInput(ProcessBuilder.Redirect.INHERIT);
        builder.redirectOutput(ProcessBuilder.Redirect.PIPE);
        builder.redirectError(ProcessBuilder.Redirect.PIPE);

        return builder;
    }

    @Override
    public Object buildConnect(Process process) {
        var env = parseStartUpEnv(getProcessOutput(process));
        var host = "localhost";
        try {
            socket = new Socket(host, env.port());
        } catch (IOException e) {
            throw new RuntimeException(Strings.format("Failed to connect to language server: {}:{}", host, env.port), e);
        }
        return socket;
    }

    @Override
    public InputStream getInputStream(Object o) throws IOException {
        return socket.getInputStream();
    }

    @Override
    public OutputStream getOutputStream(Object o) throws IOException {
        return socket.getOutputStream();
    }

    private String getExecPath(String lsHome) {
        var osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String bin;

        if (osName.contains("win")) {
            var osArch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
            bin = osArch.contains("64") ? "nbcode64.exe" : "nbcode.exe";
        } else {
            bin = "nbcode.sh";
        }

        return Paths.get(lsHome, "nbcode", "bin", bin).toString();
    }

    private NBLanguageServerConnectEnv parseStartUpEnv(String line) {
        if (line == null) {
            throw new RuntimeException("Failed to connect language server, no connection info found");
        }
        var parts = line.split(" ");
        // without hash, only port, listen-hash -> listen
        return new NBLanguageServerConnectEnv(Integer.parseInt(parts[6]), null);
    }

    private String getProcessOutput(Process process) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (true) {
                line = reader.readLine();
                if (line == null) {
                    break;
                }
                logger.info("Process output: {}", line);
                if (line.contains("Java Language Server listening at port")) {
                    return line;
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to start language server: ", e);
        }
        return null;
    }

    public record NBLanguageServerConnectEnv(int port, String hash) {
    }
}
