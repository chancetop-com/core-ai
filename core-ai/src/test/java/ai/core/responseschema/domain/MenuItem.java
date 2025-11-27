package ai.core.responseschema.domain;

import core.framework.api.json.Property;

import java.util.ArrayList;
import java.util.List;

/**
 * Menu item
 *
 * @author cyril
 */
public class MenuItem {
    // Basic fields
    @Property(name = "id")
    public String id;

    @Property(name = "name")
    public String name;

    @Property(name = "price")
    public Double price;

    @Property(name = "cost")
    public Double cost;

    @Property(name = "sales_volume")
    public Integer salesVolume;

    @Property(name = "description")
    public String description;

    @Property(name = "img")
    public String img;

    @Property(name = "thumbnail")
    public String thumbnail;

    @Property(name = "image_url")
    public String imageUrl;

    // Menu attributes
    @Property(name = "dish_tags")
    public List<String> dishTags;

    @Property(name = "temperature")
    public List<String> temperature;

    @Property(name = "popularity_indicators")
    public List<String> popularityIndicators;

    @Property(name = "allergens")
    public List<String> allergens;

    @Property(name = "ingredients")
    public List<String> ingredients;

    @Property(name = "sourcing")
    public String sourcing;

    @Property(name = "portion_size")
    public String portionSize;


    @Property(name = "spicy_level")
    public Integer spicyLevel;

    @Property(name = "estimated_calories")
    public Integer estimatedCalories;

    @Property(name = "alcohol")
    public Integer alcohol;

    @Property(name = "brewery")
    public String brewery;

    @Property(name = "recommended_pairings")
    public List<String> recommendedPairings;

    // Business metrics
    @Property(name = "food_cost")
    public Double foodCost;

    @Property(name = "profit_margin")
    public Double profitMargin;

    @Property(name = "recommended")
    public Boolean recommended;

    @Property(name = "popularity_score")
    public Integer popularityScore;

    public MenuItem() {
        this.dishTags = new ArrayList<>();
        this.temperature = new ArrayList<>();
        this.popularityIndicators = new ArrayList<>();
        this.allergens = new ArrayList<>();
        this.ingredients = new ArrayList<>();
        this.recommendedPairings = new ArrayList<>();
    }
}
