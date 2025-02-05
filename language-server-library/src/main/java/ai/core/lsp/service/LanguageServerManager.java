package ai.core.lsp.service;

import ai.core.lsp.service.client.ProjectLanguageClient;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.WorkspaceClientCapabilities;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author stephen
 */
public abstract class LanguageServerManager {
    private final Logger logger = LoggerFactory.getLogger(LanguageServerManager.class);

    private Process process;
    private LanguageServer server;
    private Timer idleTimer;
    private InputStream in;
    private OutputStream out;
    private final AtomicInteger clientCount = new AtomicInteger(0);

    private void start(LanguageServerConfig config, String workspace) {
        var builder = setupProcessBuilder(config, workspace);
        logger.info("Starting language server: {}", builder.command());

        try {
            var process = builder.start();
            setProcess(process);
        } catch (IOException e) {
            throw new RuntimeException("Failed to start language server: ", e);
        }

        var connect = buildConnect(process);
        try {
            setIn(getInputStream(connect));
            setOut(getOutputStream(connect));
        } catch (IOException e) {
            throw new RuntimeException("Failed to get socket IO stream", e);
        }

        var client = new ProjectLanguageClient();
        var launcher = LSPLauncher.createClientLauncher(client, getIn(), getOut());

        var server = launcher.getRemoteProxy();
        setServer(server);
        launcher.startListening();

        var initFuture = server.initialize(setupInitParams(workspace));

        InitializeResult rst;
        try {
            rst = initFuture.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
        if (rst == null) {
            throw new RuntimeException("Failed to get server info");
        }

        logger.info("Language Server initialized: {}", rst.getCapabilities());
    }

    private InitializeParams setupInitParams(String workspace) {
        var params = new InitializeParams();

        params.setWorkspaceFolders(List.of(new WorkspaceFolder(new File(workspace).toURI().toASCIIString(), "project")));
        var clientCapabilities = new ClientCapabilities();
        var workspaceClientCapabilities = new WorkspaceClientCapabilities();
        workspaceClientCapabilities.setApplyEdit(Boolean.TRUE);
        workspaceClientCapabilities.setWorkspaceFolders(Boolean.TRUE);
        clientCapabilities.setWorkspace(workspaceClientCapabilities);
        params.setCapabilities(clientCapabilities);

        return params;
    }

    public void stop() {
        if (process != null) {
            process.destroy();
            process = null;
            server = null;
            if (getIn() != null) {
                try {
                    getIn().close();
                } catch (IOException e) {
                    logger.error("Failed to close input stream", e);
                }
            }
            if (getOut() != null) {
                try {
                    getOut().close();
                } catch (IOException e) {
                    logger.error("Failed to close output stream", e);
                }
            }
            logger.info("Language Server stopped!");
        }
        if (idleTimer != null) {
            idleTimer.cancel();
            idleTimer = null;
        }
    }

    public LanguageServer getServer(LanguageServerConfig config, String workspace) {
        if (server == null) {
            try {
                start(config, workspace);
            } catch (Exception e) {
                throw new RuntimeException("Start language server failed: " + e.getMessage(), e);
            }
        }
        clientCount.incrementAndGet();
        resetIdleTimer();
        return server;
    }

    private void resetIdleTimer() {
        if (idleTimer != null) {
            idleTimer.cancel();
        }
        idleTimer = new Timer();
        idleTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                if (clientCount.get() == 0) {
                    logger.info("Language Server idle for too long, shutting down...");
                    stop();
                }
            }
        }, LanguageServerConfig.IDLE_TIMEOUT);
    }

    public abstract ProcessBuilder setupProcessBuilder(LanguageServerConfig config, String workspace);

    public Object buildConnect(Process process) {
        return process;
    }

    public InputStream getInputStream(Object o) throws IOException {
        return process.getInputStream();
    }

    public OutputStream getOutputStream(Object o) throws IOException {
        return process.getOutputStream();
    }

    public Process getProcess() {
        return process;
    }

    public InputStream getIn() {
        return in;
    }

    public OutputStream getOut() {
        return out;
    }

    public void setOut(OutputStream out) {
        this.out = out;
    }

    public void setIn(InputStream in) {
        this.in = in;
    }

    public void setServer(LanguageServer server) {
        this.server = server;
    }

    public void setProcess(Process process) {
        this.process = process;
    }
}
