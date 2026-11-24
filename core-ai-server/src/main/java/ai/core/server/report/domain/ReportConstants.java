package ai.core.server.report.domain;

/**
 * @author core-ai
 */
public final class ReportConstants {
    public static final int SPEC_VERSION = 1;
    public static final int ENGINE_VERSION = 1;

    public static final String MODE_AGGREGATE = "AGGREGATE";
    public static final String MODE_RECORDS = "RECORDS";

    public static final String TIME_FIELD_RUN_STARTED_AT = "meta.run_started_at";
    public static final String TIME_FIELD_CREATED_AT = "meta.created_at";
    public static final String DATA_FIELD_PREFIX = "data.";

    public static final String FILTER_OP_EQ = "EQ";
    public static final String FILTER_OP_NE = "NE";
    public static final String FILTER_OP_GT = "GT";
    public static final String FILTER_OP_GTE = "GTE";
    public static final String FILTER_OP_LT = "LT";
    public static final String FILTER_OP_LTE = "LTE";
    public static final String FILTER_OP_IN = "IN";

    public static final String RANGE_TODAY = "TODAY";
    public static final String RANGE_YESTERDAY = "YESTERDAY";
    public static final String RANGE_LAST_N_COMPLETE_DAYS = "LAST_N_COMPLETE_DAYS";
    public static final String RANGE_ROLLING_DAYS = "ROLLING_DAYS";
    public static final String RANGE_FIXED = "FIXED";

    public static final String BUCKET_NONE = "NONE";
    public static final String BUCKET_HOUR = "HOUR";
    public static final String BUCKET_DAY = "DAY";
    public static final String BUCKET_WEEK = "WEEK";
    public static final String BUCKET_MONTH = "MONTH";

    public static final String OP_SUM = "SUM";
    public static final String OP_AVG = "AVG";
    public static final String OP_MIN = "MIN";
    public static final String OP_MAX = "MAX";
    public static final String OP_COUNT = "COUNT";
    public static final String OP_LAST = "LAST";
    public static final String OP_RATIO = "RATIO";

    public static final String DISPLAY_METRIC = "METRIC";
    public static final String DISPLAY_LINE = "LINE";
    public static final String DISPLAY_BAR = "BAR";
    public static final String DISPLAY_TABLE = "TABLE";

    public static final String FORMAT_NUMBER = "NUMBER";
    public static final String FORMAT_PERCENT = "PERCENT";
    public static final String FORMAT_CURRENCY = "CURRENCY";

    public static final String STATUS_READY = "READY";
    public static final String STATUS_NO_DATA = "NO_DATA";

    public static final String FRESHNESS_WITHIN_EXPECTATION = "WITHIN_EXPECTATION";
    public static final String FRESHNESS_DELAYED = "DELAYED";
    public static final String FRESHNESS_UNKNOWN = "UNKNOWN";

    public static final String WIDTH_HALF = "HALF";
    public static final String WIDTH_FULL = "FULL";

    public static final String ACTION_CREATED = "CREATED";
    public static final String ACTION_UPDATED = "UPDATED";
    public static final String ACTION_DELETED = "DELETED";
    public static final String ACTION_PINNED = "PINNED";
    public static final String ACTION_UNPINNED = "UNPINNED";
    public static final String ACTION_REORDERED = "REORDERED";
    public static final String ACTION_WIDTH_CHANGED = "WIDTH_CHANGED";

    private ReportConstants() {
    }
}
