package ai.core.server;

import ai.core.server.apiuser.PgApiKeyRow;
import ai.core.server.pg.PgDocumentRow;
import ai.core.server.project.PgProjectCountRow;
import ai.core.server.project.PgProjectRow;
import ai.core.server.project.PgSubjectFileStatsRow;
import ai.core.server.skill.PgSkillCountRow;
import ai.core.server.user.PgUserIdRow;
import ai.core.server.user.PgUserRow;
import core.framework.module.Module;

/**
 * Configures the shared PostgreSQL database before any module binds a PostgreSQL store. Dataset and
 * native stores share one database; each store owns its tables and SQL.
 *
 * @author stephen
 */
public class PostgresModule extends Module {
    @Override
    protected void initialize() {
        var storeMode = property("sys.store.mode").orElse("mongo");
        var datasetStore = property("sys.dataset.store").orElse("mongo");
        var migrationEnabled = Boolean.parseBoolean(property("sys.dataset.migration.enabled").orElse("false"));
        if (!"pg".equals(storeMode) && !"postgresql".equals(datasetStore) && !migrationEnabled) return;

        var url = requiredProperty("sys.db.url");
        var user = requiredProperty("sys.db.user");
        var password = requiredProperty("sys.db.password");
        db().url(url);
        db().user(user);
        db().password(password);
        db().view(PgDocumentRow.class);
        db().view(PgApiKeyRow.class);
        db().view(PgProjectRow.class);
        db().view(PgProjectCountRow.class);
        db().view(PgSubjectFileStatsRow.class);
        db().view(PgSkillCountRow.class);
        db().view(PgUserRow.class);
        db().view(PgUserIdRow.class);
    }
}
