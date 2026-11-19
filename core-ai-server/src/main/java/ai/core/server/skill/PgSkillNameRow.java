package ai.core.server.skill;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgSkillNameRow {
    @Column(name = "id")
    public String id;

    @Column(name = "name")
    public String name;
}
