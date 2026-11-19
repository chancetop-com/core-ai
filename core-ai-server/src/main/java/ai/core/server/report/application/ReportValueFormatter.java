package ai.core.server.report.application;

import ai.core.server.report.domain.ReportConstants;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * @author core-ai
 */
final class ReportValueFormatter {
    static String value(Object value, String op) {
        if (value == null) return null;
        if (value instanceof Number number) return new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
        if (value instanceof String text) {
            if (ReportConstants.OP_LAST.equalsIgnoreCase(op)) return text;
            if (text.startsWith("20") && text.contains("T")) return normalizeTime(text);
            return text;
        }
        return String.valueOf(value);
    }

    static BigDecimal decimal(Object value) {
        if (value == null) return null;
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String normalizeTime(String value) {
        try {
            return Instant.parse(value).toString();
        } catch (DateTimeParseException e) {
            return normalizeOffsetTime(value);
        }
    }

    private static String normalizeOffsetTime(String value) {
        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toString();
        } catch (DateTimeParseException e) {
            return value;
        }
    }

    private ReportValueFormatter() {
    }
}
