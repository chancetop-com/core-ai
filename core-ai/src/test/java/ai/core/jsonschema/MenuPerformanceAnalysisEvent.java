package ai.core.jsonschema;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.List;

/**
 * @author stephen
 */
public class MenuPerformanceAnalysisEvent extends BaseEvent {
    public static MenuPerformanceAnalysisEvent of(LLMAnalysisResult result) {
        var event = new MenuPerformanceAnalysisEvent();
        var tableConfig = new TableConfig();
        tableConfig.title = "Menu Performance";
        tableConfig.description = "Menu performance analysis table.";
        tableConfig.editable = false;
        tableConfig.columns = List.of(
                ColumnConfig.of("item_id", "Item ID", "string", false, true),
                ColumnConfig.of("item_name", "Item Name", "string", false, false),
                ColumnConfig.of("profit_margin", "Profit Margin", "string", false, false),
                ColumnConfig.of("popularity", "Popularity", "string", false, false),
                ColumnConfig.of("quadrant", "Quadrant", "string", false, false),
                ColumnConfig.of("strategy_tag", "Strategy Tag", "string", false, false)
        );
        event.tableConfig = tableConfig;
        event.keySummary = result.keySummary;
        event.tableData = result.tableData;
        return event;
    }


    @NotNull
    @Property(name = "table_config")
    public TableConfig tableConfig;

    @NotNull
    @Property(name = "table_data")
    public List<TableDataItem> tableData;

    @Property(name = "key_summary")
    public KeySummary keySummary;

    public MenuPerformanceAnalysisEvent() {
        super(EventType.MENU_PERFORMANCE_ANALYSIS);
    }

    public static class TableConfig {
        @Property(name = "title")
        public String title;

        @Property(name = "description")
        public String description;

        @Property(name = "editable")
        public Boolean editable;

        @Property(name = "columns")
        public List<ColumnConfig> columns;
    }

    public static class ColumnConfig {
        public static ColumnConfig of(String key, String title, String type, Boolean editable, Boolean hidden) {
            var column = new ColumnConfig();
            column.key = key;
            column.title = title;
            column.type = type;
            column.editable = editable;
            column.hidden = hidden;
            return column;
        }

        @Property(name = "key")
        public String key;

        @Property(name = "title")
        public String title;

        @Property(name = "type")
        public String type;

        @Property(name = "editable")
        public Boolean editable;

        @Property(name = "hidden")
        public Boolean hidden;
    }

    public static class TableDataItem {
        @Property(name = "item_id")
        public String itemId;

        @Property(name = "item_name")
        public String itemName;

        @Property(name = "profit_margin")
        public String profitMargin;

        @Property(name = "popularity")
        public String popularity;

        @Property(name = "quadrant")
        public String quadrant;

        @Property(name = "strategy_tag")
        public String strategyTag;
    }

    public static class KeySummary {
        @Property(name = "good_performance")
        public String goodPerformance;

        @Property(name = "areas_for_improvement")
        public String areasForImprovement;
    }

    public static class LLMAnalysisResult {
        @NotNull
        @Property(name = "table_data")
        public List<TableDataItem> tableData;

        @NotNull
        @Property(name = "key_summary")
        public KeySummary keySummary;
    }
}
