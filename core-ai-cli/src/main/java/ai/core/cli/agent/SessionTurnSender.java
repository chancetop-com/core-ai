package ai.core.cli.agent;

import ai.core.cli.listener.CliEventListener;
import ai.core.cli.ui.FileReferenceExpander;
import ai.core.cli.ui.TerminalUI;
import ai.core.session.InProcessAgentSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Semaphore;

/** Sends user input and scheduled triggers to the session and waits for the rendered turn to finish. */
final class SessionTurnSender {
    private static final Logger LOGGER = LoggerFactory.getLogger(SessionTurnSender.class);

    static void startSenderThread(BlockingQueue<String> queue, CliEventListener listener, InProcessAgentSession session,
                                  Semaphore readyForInput, String poison) {
        var senderThread = new Thread(() -> {
            try {
                while (true) {
                    var message = queue.take();
                    if (poison.equals(message)) break;
                    sendMessage(listener, session, readyForInput, message);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "sender-thread");
        senderThread.setDaemon(true);
        senderThread.start();
    }

    static void sendPrompt(CliEventListener listener, InProcessAgentSession session, String prompt, TerminalUI ui) {
        LOGGER.debug("sending prompt: {}", prompt);
        listener.prepareTurn();
        var expanded = ui.getPasteBuffer().expand(prompt == null ? "" : prompt);
        try {
            session.sendMessage(FileReferenceExpander.expand(expanded));
        } finally {
            listener.getPanel().stopSpinnerIfActive();
        }
        LOGGER.debug("waiting for turn...");
        listener.waitForTurn();
        LOGGER.debug("turn finished");
    }

    private static void sendMessage(CliEventListener listener, InProcessAgentSession session,
                                    Semaphore readyForInput, String message) {
        LOGGER.debug("sending message: {}", message);
        listener.prepareTurn();
        try {
            session.sendMessage(message);
        } finally {
            listener.getPanel().stopSpinnerIfActive();
        }
        LOGGER.debug("waiting for turn...");
        listener.waitForTurn();
        LOGGER.debug("turn finished");
        readyForInput.release();
    }

    private SessionTurnSender() {
    }
}
