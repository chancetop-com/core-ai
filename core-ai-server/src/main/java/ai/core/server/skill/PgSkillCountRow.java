package ai.core.server.skill;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgSkillCountRow {
    @Column(name = "total")
    public Long total;
}
