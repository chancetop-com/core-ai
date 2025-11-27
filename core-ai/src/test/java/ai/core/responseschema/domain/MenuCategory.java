package ai.core.responseschema.domain;

import core.framework.api.json.Property;

import java.util.ArrayList;
import java.util.List;

/**
 * Menu category
 *
 * @author cyril
 */
public class MenuCategory {
    @Property(name = "id")
    public String id;

    @Property(name = "name")
    public String name;

    @Property(name = "product_list")
    public List<MenuItem> productList;

    public MenuCategory() {
        this.productList = new ArrayList<>();
    }
}
