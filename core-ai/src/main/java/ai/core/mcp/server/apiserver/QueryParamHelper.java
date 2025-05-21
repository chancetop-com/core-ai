package ai.core.mcp.server.apiserver;

import core.framework.internal.web.service.PathParamHelper;
import core.framework.json.JSON;
import core.framework.web.exception.BadRequestException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * @author stephen
 */
public final class QueryParamHelper {

    public static String toString(Object value, Class<?> type) {
        if (value == null || type == null) return null;

        if (String.class.equals(type)) {
            return toString((String) value);
        } else if (Number.class.isAssignableFrom(type)) {
            return toString((Number) value);
        } else if (Boolean.class.equals(type)) {
            return toString((Boolean) value);
        } else if (LocalDate.class.equals(type)) {
            return toString((LocalDate) value);
        } else if (LocalDateTime.class.equals(type)) {
            return toString((LocalDateTime) value);
        } else if (LocalTime.class.equals(type)) {
            return toString((LocalTime) value);
        } else if (ZonedDateTime.class.equals(type)) {
            return toString((ZonedDateTime) value);
        } else if (Enum.class.isAssignableFrom(type)) {
            return toString((Enum<?>) value);
        } else {
            throw new BadRequestException("unsupported type for toString: " + type.getName(), "INVALID_HTTP_REQUEST");
        }
    }


    public static String toString(Number value) {
        return value == null ? null : value.toString();
    }

    public static String toString(Boolean value) {
        return value == null ? null : value.toString();
    }

    public static String toString(LocalDateTime dateTime) {
        return dateTime == null ? null : DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(dateTime);
    }

    public static String toString(LocalDate date) {
        return date == null ? null : DateTimeFormatter.ISO_LOCAL_DATE.format(date);
    }

    public static String toString(LocalTime time) {
        return time == null ? null : DateTimeFormatter.ISO_LOCAL_TIME.format(time);
    }

    public static String toString(ZonedDateTime dateTime) {
        return dateTime == null ? null : DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(dateTime);
    }

    public static <T extends Enum<?>> String toString(T enumValue) {
        return enumValue == null ? null : JSON.toEnumValue(enumValue);
    }

    public static String toString(String value) {
        return value.isEmpty() ? null : value;
    }

    public static Integer toInt(String value) {
        return value.isEmpty() ? null : PathParamHelper.toInt(value);
    }

    public static Long toLong(String value) {
        return value.isEmpty() ? null : PathParamHelper.toLong(value);
    }

    public static Double toDouble(String value) {
        if (value.isEmpty()) {
            return null;
        } else {
            try {
                return Double.valueOf(value);
            } catch (NumberFormatException e) {
                throw new BadRequestException("failed to parse double, value=" + value, "INVALID_HTTP_REQUEST", e);
            }
        }
    }

    public static BigDecimal toBigDecimal(String value) {
        if (value.isEmpty()) {
            return null;
        } else {
            try {
                return new BigDecimal(value);
            } catch (NumberFormatException e) {
                throw new BadRequestException("failed to parse big decimal, value=" + value, "INVALID_HTTP_REQUEST", e);
            }
        }
    }

    public static Boolean toBoolean(String value) {
        return value.isEmpty() ? null : Boolean.valueOf(value);
    }

    public static <T extends Enum<?>> T toEnum(String value, Class<T> valueClass) {
        return value.isEmpty() ? null : PathParamHelper.toEnum(value, valueClass);
    }

    public static ZonedDateTime toZonedDateTime(String value) {
        if (value.isEmpty()) {
            return null;
        } else {
            try {
                return ZonedDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            } catch (DateTimeParseException e) {
                throw new BadRequestException("failed to parse zoned date time, value=" + value, "INVALID_HTTP_REQUEST", e);
            }
        }
    }

    public static LocalDateTime toDateTime(String value) {
        if (value.isEmpty()) {
            return null;
        } else {
            try {
                return LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            } catch (DateTimeParseException e) {
                throw new BadRequestException("failed to parse local date time, value=" + value, "INVALID_HTTP_REQUEST", e);
            }
        }
    }

    public static LocalTime toTime(String value) {
        if (value.isEmpty()) {
            return null;
        } else {
            try {
                return LocalTime.parse(value, DateTimeFormatter.ISO_LOCAL_TIME);
            } catch (DateTimeParseException e) {
                throw new BadRequestException("failed to parse local time, value=" + value, "INVALID_HTTP_REQUEST", e);
            }
        }
    }

    public static LocalDate toDate(String value) {
        if (value.isEmpty()) {
            return null;
        } else {
            try {
                return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
            } catch (DateTimeParseException e) {
                throw new BadRequestException("failed to parse local date, value=" + value, "INVALID_HTTP_REQUEST", e);
            }
        }
    }
}
