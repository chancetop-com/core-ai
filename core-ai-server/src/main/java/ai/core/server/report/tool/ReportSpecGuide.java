package ai.core.server.report.tool;

/**
 * LLM-facing description of the spec_version=1 report contract. Kept in one place so the chat report
 * tools and the self-harness dataset tools cannot drift apart.
 *
 * @author core-ai
 */
public final class ReportSpecGuide {
    public static final String TEXT = """

            ReportSpec JSON (spec_version=1):
            {"spec_version":1,
             "source":{"dataset_id":"<dataset id>"},
             "parameters":[{"name":"stores","type":"STRING","multiple":true,"default":[],"options_field":"store"},
                           {"name":"range","type":"DATE_RANGE","default":{"kind":"LAST_N_DAYS_OF_DATA","days":30}}],
             "components":[
               {"id":"kpis","title":"Summary","query":{"mode":"AGGREGATE","time_field":"data.business_date",
                 "time_range":{"param":"range"},"timezone":"Asia/Shanghai","bucket":"NONE",
                 "filters":[{"field":"store","op":"IN","param":"stores"}],
                 "measures":[{"id":"impressions","label":"Impressions","op":"SUM","field":"data.impressions"},
                             {"id":"desktop_maps","label":"Desktop Maps","op":"SUM","field":"data.desktop_maps"},
                             {"id":"desktop_search","label":"Desktop Search","op":"SUM","field":"data.desktop_search"}],
                 "derived_measures":[{"id":"desktop","label":"Desktop","op":"ADD","inputs":["desktop_maps","desktop_search"]},
                                     {"id":"desktop_share","label":"Desktop share","op":"RATIO","numerator":"desktop","denominator":"impressions"}]},
                "display":{"type":"METRIC_GROUP","series":[{"field":"impressions","format":"NUMBER"},
                                                           {"field":"desktop"},{"field":"desktop_share","format":"PERCENT"}]}},
               {"id":"ranking","title":"Top stores","query":{"mode":"AGGREGATE","time_field":"data.business_date",
                 "time_range":{"param":"range"},"timezone":"Asia/Shanghai","bucket":"NONE","category_field":"store",
                 "filters":[{"field":"store","op":"IN","param":"stores"}],
                 "measures":[{"id":"impressions","op":"SUM","field":"data.impressions"}],
                 "order_by":{"measure":"impressions","direction":"DESC"},"limit":10},
                "display":{"type":"BAR","orientation":"HORIZONTAL","series":[{"field":"impressions","label":"Impressions"}]}}
             ]}

            Rules:
            - A legacy single-chart report may omit parameters/components and use top-level query/display. A dashboard uses components (max 12); each component has its own query/display.
            - parameters: name, type=STRING|NUMBER|DATE_RANGE, multiple (STRING/NUMBER only), default, optional options_field (top-level schema field; the UI lists its distinct values). Defaults are required for any parameter used by a preview/create. Query time_range.param references a DATE_RANGE parameter; filters[].param references a declared parameter (value and param are mutually exclusive).
            - mode=AGGREGATE for metrics and charts; mode=RECORDS for a record list (display TABLE only; requires record_fields with top-level schema field names, and no measures).
            - time_field: prefer a business date, data.<field> for a schema field declared DATE or DATETIME. meta.created_at/meta.run_started_at are ingestion time and only for event datasets; using meta on a backfilled snapshot collapses all history into the ingestion day. A STRING field is not a time field; change the schema to DATE/DATETIME first.
            - time_range.kind=TODAY|YESTERDAY|LAST_N_COMPLETE_DAYS|LAST_N_DAYS_OF_DATA|SOURCE_RANGE|ROLLING_DAYS (days 1..366)|FIXED (ISO-8601 instant or ISO date from/to). LAST_N_DAYS_OF_DATA and SOURCE_RANGE anchor to the dataset's min/max time instead of today; use them for lagging snapshots. timezone is a required IANA zone.
            - bucket=NONE|HOUR|DAY|WEEK|MONTH. A DATE time field supports NONE|DAY|WEEK|MONTH, not HOUR. category_field is one optional top-level schema field. order_by={measure,direction DESC|ASC} and limit (1..50) apply to a category dimension and are how top-N rankings are built.
            - filters match top-level schema fields; op=EQ|NE|GT|GTE|LT|LTE|IN (default EQ). IN takes a non-empty array (or a multiple parameter). A DATE value is an ISO date; a DATETIME value is ISO date-time with an offset.
            - measures: op=SUM|AVG|MIN|MAX|COUNT|COUNT_DISTINCT|LAST. COUNT needs no field; SUM/AVG/MIN/MAX need a NUMBER field; COUNT_DISTINCT and LAST accept any field; fields use field="data.<schema field>".
            - derived_measures: op=ADD (inputs=[2+ previously declared measure ids]), RATIO (numerator/denominator previously declared ids; ratio of sums), MOVING_AVG (input=<measure id>, window 2..366; requires a time bucket). Base + derived measures are capped at 12; references must point to earlier measures.
            - display.type=METRIC (exactly one output measure), METRIC_GROUP (one row of KPI cards; no bucket/category), LINE or BAR (exactly one of bucket/category; BAR supports orientation=HORIZONTAL), MEASURE_BAR (one row of measures drawn as horizontal bars; use for composition), or TABLE. display.series lists the output measure ids and may omit dependency measures that are only used to compute a derived value; format=NUMBER|PERCENT|CURRENCY; width=HALF|FULL.
            - category_field, filters[].field, record_fields and options_field use top-level schema names; a leading data. prefix is accepted and normalized.
            - A dashboard is stored as one report and is pinned from the report page by the user; reports are never pinned automatically.
            """;

    private ReportSpecGuide() {
    }
}
