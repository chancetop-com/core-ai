package ai.core.responseschema.domain;

import core.framework.api.json.Property;

import java.util.ArrayList;
import java.util.List;

/**
 * Type-safe menu data structure
 *
 * Replaces Map&lt;String, Object&gt; with strongly-typed classes
 * Use JsonUtil for conversion between Map and MenuData
 *
 * @author cyril
 */
public class MenuData {
    @Property(name = "restaurant_info")
    public RestaurantInfo restaurantInfo;

    @Property(name = "categories")
    public List<MenuCategory> categories;

    public MenuData() {
        this.restaurantInfo = new RestaurantInfo();
        this.categories = new ArrayList<>();
    }

    /**
     * Check if menu data is empty
     */
    public boolean isEmpty() {
        return categories == null || categories.isEmpty();
    }
}
