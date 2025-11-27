package ai.core.responseschema.domain;

import core.framework.api.json.Property;

/**
 * Restaurant information
 *
 * @author cyril
 */
public class RestaurantInfo {
    @Property(name = "id")
    public String id;

    @Property(name = "merchant_id")
    public String merchantId;

    @Property(name = "name")
    public String name;

    @Property(name = "description")
    public String description;
}
