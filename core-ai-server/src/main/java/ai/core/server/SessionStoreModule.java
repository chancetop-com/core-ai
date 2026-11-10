package ai.core.server;

import ai.core.server.session.ChatMessageMigrationService;
import ai.core.server.session.ChatMessageStore;
import ai.core.server.session.ChatSessionMigrationService;
import ai.core.server.session.ChatSessionStore;
import ai.core.server.session.MongoChatMessageStore;
import ai.core.server.session.MongoChatSessionStore;
import ai.core.server.session.PgChatMessageStore;
import ai.core.server.session.PgChatSessionStore;
import core.framework.module.Module;

/**
 * Binds the chat session store before any module that injects it. ProjectAttributionModule builds
 * ProjectArtifactBinder during platform startup, earlier than the session runtime module.
 *
 * @author stephen
 */
public class SessionStoreModule extends Module {
    @Override
    protected void initialize() {
        if ("pg".equals(property("sys.store.mode").orElse("mongo"))) {
            bind(ChatSessionStore.class, bind(PgChatSessionStore.class));
            bind(ChatMessageStore.class, bind(PgChatMessageStore.class));
            var sessionMigration = bind(ChatSessionMigrationService.class);
            onStartup(sessionMigration::migrate);
            var messageMigration = bind(ChatMessageMigrationService.class);
            onStartup(messageMigration::migrate);
        } else {
            bind(ChatSessionStore.class, bind(MongoChatSessionStore.class));
            bind(ChatMessageStore.class, bind(MongoChatMessageStore.class));
        }
    }
}
