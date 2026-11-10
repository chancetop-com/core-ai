package ai.core.server;

import ai.core.server.channel.MongoUserChannelTargetStore;
import ai.core.server.channel.PgUserChannelTargetStore;
import ai.core.server.channel.UserChannelTargetStore;
import ai.core.server.domain.GeminiFileRepository;
import ai.core.server.domain.MongoGeminiFileRepository;
import ai.core.server.domain.MongoSessionAttachmentRefRepository;
import ai.core.server.domain.PgGeminiFileRepository;
import ai.core.server.domain.PgSessionAttachmentRefRepository;
import ai.core.server.domain.SessionAttachmentRefRepository;
import ai.core.server.session.MongoSessionFeedbackStore;
import ai.core.server.session.MongoSessionNoticeStore;
import ai.core.server.session.PgSessionFeedbackStore;
import ai.core.server.session.PgSessionNoticeStore;
import ai.core.server.session.SessionFeedbackStore;
import ai.core.server.session.SessionNoticeStore;
import ai.core.server.small.SmallCollectionMigrationService;
import ai.core.server.web.foryou.MongoUserReportStore;
import ai.core.server.web.foryou.MongoUserTodoStore;
import ai.core.server.web.foryou.PgUserReportStore;
import ai.core.server.web.foryou.PgUserTodoStore;
import ai.core.server.web.foryou.UserReportStore;
import ai.core.server.web.foryou.UserTodoStore;
import core.framework.module.Module;

/**
 * Binds the small G2 stores before the channel/session/gateway modules that consume them.
 *
 * @author stephen
 */
public class SmallCollectionStoreModule extends Module {
    @Override
    protected void initialize() {
        if ("pg".equals(property("sys.store.mode").orElse("mongo"))) {
            bind(SessionFeedbackStore.class, bind(PgSessionFeedbackStore.class));
            bind(UserChannelTargetStore.class, bind(PgUserChannelTargetStore.class));
            bind(SessionNoticeStore.class, bind(PgSessionNoticeStore.class));
            bind(SessionAttachmentRefRepository.class, bind(PgSessionAttachmentRefRepository.class));
            bind(GeminiFileRepository.class, bind(PgGeminiFileRepository.class));
            bind(UserReportStore.class, bind(PgUserReportStore.class));
            bind(UserTodoStore.class, bind(PgUserTodoStore.class));
            var migration = bind(SmallCollectionMigrationService.class);
            onStartup(migration::migrate);
        } else {
            bind(SessionFeedbackStore.class, bind(MongoSessionFeedbackStore.class));
            bind(UserChannelTargetStore.class, bind(MongoUserChannelTargetStore.class));
            bind(SessionNoticeStore.class, bind(MongoSessionNoticeStore.class));
            bind(SessionAttachmentRefRepository.class, bind(MongoSessionAttachmentRefRepository.class));
            bind(GeminiFileRepository.class, bind(MongoGeminiFileRepository.class));
            bind(UserReportStore.class, bind(MongoUserReportStore.class));
            bind(UserTodoStore.class, bind(MongoUserTodoStore.class));
        }
    }
}
