package ai.core.cli.remote;

import ai.core.api.server.session.AgentSession;
import ai.core.cli.listener.RemoteEventListener;
import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.FileReferenceExpander;
import ai.core.cli.ui.TerminalUI;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;

/**
 * @author stephen
 */
public class BuildLLMCallCommandHandler {
    private static final String POISON_PILL = "\0__EXIT__";
    private static final String BUILDER_AGENT_ID = "llm-call-builder";

    private final TerminalUI ui;
    private final RemoteApiClient api;

    public BuildLLMCallCommandHandler(TerminalUI ui, RemoteApiClient api) {
        this.ui = ui;
        this.api = api;
    }

    public void handle() {
        ui.printStreamingChunk("\n" + AnsiTheme.PROMPT + "  LLM Call Builder" + AnsiTheme.RESET + "\n");
        ui.printStreamingChunk(AnsiTheme.MUTED + "  Creating builder session..." + AnsiTheme.RESET + "\n");

        HttpAgentSession session;
        try {
            session = HttpAgentSession.connect(api, BUILDER_AGENT_ID);
        } catch (RuntimeException e) {
            ui.showError("failed to create builder session: " + e.getMessage());
            return;
        }
        var listener = new RemoteEventListener(ui, session);
        session.onEvent(listener);

        ui.printStreamingChunk(AnsiTheme.MUTED + "  Session: " + session.id() + AnsiTheme.RESET + "\n");
        ui.printStreamingChunk(AnsiTheme.MUTED + "  Describe the LLM Call API you want to build. Type /done to finish." + AnsiTheme.RESET + "\n");

        runInteractiveLoop(session, listener);

        session.close();
        ui.printStreamingChunk("\n" + AnsiTheme.MUTED + "  Builder session closed." + AnsiTheme.RESET + "\n\n");
    }

    private void runInteractiveLoop(AgentSession session, RemoteEventListener listener) {
        BlockingQueue<String> messageQueue = new LinkedBlockingQueue<>();
        Semaphore readyForInput = new Semaphore(1);

        startSenderThread(session, messageQueue, listener, readyForInput);

        while (true) {
            try {
                readyForInput.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            ui.printInputFrame();
            var input = ui.readInput("builder> ");
            if (input == null || "/done".equalsIgnoreCase(input.trim()) || "/exit".equalsIgnoreCase(input.trim())) {
                messageQueue.offer(POISON_PILL);
                break;
            }
            if (input.isBlank()) {
                readyForInput.release();
                continue;
            }
            messageQueue.offer(FileReferenceExpander.expand(input));
        }
    }

    private void startSenderThread(AgentSession session, BlockingQueue<String> queue,
                                   RemoteEventListener listener, Semaphore readyForInput) {
        var thread = new Thread(() -> {
            try {
                while (true) {
                    String msg = queue.take();
                    if (POISON_PILL.equals(msg)) break;
                    listener.prepareTurn();
                    session.sendMessage(msg);
                    listener.waitForTurn();
                    readyForInput.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "builder-sender");
        thread.setDaemon(true);
        thread.start();
    }
}
