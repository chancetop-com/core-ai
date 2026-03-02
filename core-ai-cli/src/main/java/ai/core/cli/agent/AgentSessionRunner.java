package ai.core.cli.agent;

import ai.core.cli.DebugLog;
import ai.core.cli.command.ReplCommandHandler;
import ai.core.cli.listener.CliEventListener;
import ai.core.cli.ui.BannerPrinter;
import ai.core.cli.ui.TerminalUI;
import ai.core.llm.LLMProviders;
import ai.core.session.InProcessAgentSession;

import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;

/**
 * @author stephen
 */
public class AgentSessionRunner {

    private static final String POISON_PILL = "\0__EXIT__";

    private final TerminalUI ui;
    private final LLMProviders providers;
    private final String modelOverride;
    private final boolean autoApproveAll;
    private final int maxTurn;

    public AgentSessionRunner(TerminalUI ui, LLMProviders providers, String modelOverride, boolean autoApproveAll, int maxTurn) {
        this.ui = ui;
        this.providers = providers;
        this.modelOverride = modelOverride;
        this.autoApproveAll = autoApproveAll;
        this.maxTurn = maxTurn;
    }

    public void run() {
        var sessionId = UUID.randomUUID().toString();
        var agent = CliAgent.of(providers, modelOverride, maxTurn);
        var session = new InProcessAgentSession(sessionId, agent, autoApproveAll);
        var listener = new CliEventListener(ui, session);
        session.onEvent(listener);

        var commands = new ReplCommandHandler(ui);
        BlockingQueue<String> messageQueue = new LinkedBlockingQueue<>();
        Semaphore readyForInput = new Semaphore(1);

        printBanner();
        startSenderThread(messageQueue, listener, session, readyForInput);
        readInputLoop(commands, messageQueue, readyForInput);

        session.close();
        ui.printStreamingChunk("Goodbye!\n");
    }

    private void printBanner() {
        String modelName = modelOverride != null ? modelOverride : providers.getProvider().config.getModel();
        BannerPrinter.print(ui.getWriter(), ui.getTerminalWidth(), modelName);
        // TODO: temporary diagnostic, remove after confirming terminal type
        ui.getWriter().println("[diag] terminal: type=" + ui.getTerminalType()
                + ", jline=" + ui.isJLineEnabled() + ", ansi=" + ui.isAnsiSupported());
        ui.getWriter().flush();
    }

    private void startSenderThread(BlockingQueue<String> queue, CliEventListener listener,
                                   InProcessAgentSession session, Semaphore readyForInput) {
        Thread senderThread = new Thread(() -> {
            try {
                while (true) {
                    String msg = queue.take();
                    if (POISON_PILL.equals(msg)) {
                        break;
                    }
                    DebugLog.log("sending message: " + msg);
                    listener.prepareTurn();
                    session.sendMessage(msg);
                    DebugLog.log("waiting for turn...");
                    listener.waitForTurn();
                    DebugLog.log("turn finished");
                    readyForInput.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "sender-thread");
        senderThread.setDaemon(true);
        senderThread.start();
    }

    private void readInputLoop(ReplCommandHandler commands, BlockingQueue<String> queue, Semaphore readyForInput) {
        while (true) {
            waitForReady(readyForInput);
            var input = ui.readInput();
            if (input == null || "/exit".equalsIgnoreCase(input.trim())) {
                queue.offer(POISON_PILL);
                break;
            }
            if (input.isBlank()) {
                continue;
            }
            if (input.trim().startsWith("/")) {
                commands.handle(input);
                continue;
            }
            queue.offer(input);
        }
    }

    private void waitForReady(Semaphore readyForInput) {
        try {
            readyForInput.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
