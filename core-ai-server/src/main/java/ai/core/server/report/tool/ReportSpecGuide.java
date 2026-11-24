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
             "parameters":[{"name":"stores","type":"STRING","multiple":true,"default":[]},
                           {"name":"range","type":"DATE_RANGE","default":{"kind":"LAST_N_COMPLETE_DAYS","days":30}}],
             "components":[
               {"id":"kpi","title":"Summary","query":{"mode":"AGGREGATE","time_field":"meta.created_at",
                 "time_range":{"param":"range"},"timezone":"Asia/Shanghai","bucket":"NONE",
                 "filters":[{"field":"status","op":"IN","param":"stores"}],
                 "measures":[{"id":"records","label":"Records","op":"COUNT"}]},
                "display":{"type":"METRIC"}},
               {"id":"trend","title":"Trend","query":{...},"display":{"type":"LINE","x":"bucket","series":[...]}}
             ]}

            Rules:
            - A legacy single-chart report may omit parameters/components and use top-level query/display. A dashboard uses components (max 12); each component has its own query/display.
            - parameters: name, type=STRING|NUMBER|DATE_RANGE, multiple (STRING/NUMBER only), default. Defaults are required for any parameter used by a preview/create. Query time_range.param references a DATE_RANGE parameter; filters[].param references a declared parameter (value and param are mutually exclusive).
            - mode=AGGREGATE for metrics and charts; mode=RECORDS for a record list (display TABLE only; requires record_fields with top-level schema field names, and no measures).
            - time_field=meta.created_at (ingestion time) or meta.run_started_at, or data.<field> for a schema field declared DATE or DATETIME (business time). A STRING field is not a time field; change the dataset schema to DATE/DATETIME first.
            - time_range.kind=TODAY|YESTERDAY|LAST_N_COMPLETE_DAYS|ROLLING_DAYS (days 1..366)|FIXED (ISO-8601 from/to). timezone is a required IANA zone.
            - bucket=NONE|HOUR|DAY|WEEK|MONTH. A DATE time field supports NONE|DAY|WEEK|MONTH, not HOUR. category_field is one optional top-level schema field.
            - filters match top-level schema fields; op=EQ|NE|GT|GTE|LT|LTE|IN (default EQ). IN takes a non-empty array (or a multiple parameter). A DATE value is an ISO date; a DATETIME value is ISO date-time with an offset.
            - measures: op=SUM|AVG|MIN|MAX|COUNT|LAST. COUNT needs no field; the others use field="data.<schema field>" (NUMBER except LAST). Base and derived measures are capped at 8; derived op=RATIO with numerator/denominator set to base measure ids.
            - display.type=METRIC (exactly one measure, no bucket/category), LINE or BAR (exactly one of bucket/category; series reference measures; format=NUMBER|PERCENT|CURRENCY), or TABLE.
            - category_field, filters[].field and record_fields use top-level schema names; a leading data. prefix is accepted and normalized.
            - A dashboard is stored as one report and is pinned from the report page by the user; reports are never pinned automatically.
            """;

    private ReportSpecGuide() {
    }
}
