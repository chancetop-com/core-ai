package ai.core.server;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgSettingRow {
    @Column(name = "value")
    public String value;
}
