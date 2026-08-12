package ai.core.api.server.rbac;

import core.framework.api.json.Property;

import java.util.List;
import java.util.Map;

/**
 * Role definitions: role name -> granted action permissions.
 * The {@code admin} role is implicit (wildcard) and never part of the map.
 * {@code catalog} is the complete permission catalog, returned on GET only
 * (ignored on PUT).
 *
 * @author stephen
 */
public class RoleConfigView {
    @Property(name = "roles")
    public Map<String, List<String>> roles;

    @Property(name = "catalog")
    public List<String> catalog;
}
