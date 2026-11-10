package ai.core.server.channel;

import ai.core.server.domain.UserChannelTarget;
import ai.core.server.pg.PgDocumentStore;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.util.Optional;

/**
 * @author stephen
 */
public class PgUserChannelTargetStore extends UserChannelTargetStore {
    private static final String TABLE = "app.user_channel_targets";

    @Inject
    public Database database;

    private PgDocumentStore<UserChannelTarget> store() {
        return new PgDocumentStore<>(database, TABLE, UserChannelTarget.class, target -> target.id);
    }

    @Override
    protected Optional<UserChannelTarget> get(String id) {
        return store().get(id);
    }

    @Override
    protected void insert(UserChannelTarget target) {
        store().insert(target);
    }

    @Override
    protected void replace(UserChannelTarget target) {
        store().replace(target);
    }

    @Override
    public boolean insertIfAbsent(UserChannelTarget target) {
        return store().insertIfAbsent(target);
    }
}

