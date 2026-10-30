package ai.core.session;

import ai.core.utils.JsonUtil;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates the platform-defined card shape carried as the optional companion field of a custom event
 * (see design-session-rich-cards.md). The vocabulary is closed and additive-only; clients skip unknown
 * blocks, so a card that passes this validation always renders on every client that knows the vocabulary.
 *
 * @author stephen
 */
public final class RichCards {
    public static final int MAX_BLOCKS = 32;
    public static final int MAX_ACTIONS = 6;
    public static final int MAX_TABLE_COLUMNS = 12;
    public static final int MAX_FORM_FIELDS = 20;
    public static final int MAX_SELECT_OPTIONS = 50;
    public static final int MAX_CHOICE_OPTIONS = 10;

    private static final Set<String> BLOCK_TYPES = Set.of("text", "key_values", "table", "image", "actions", "divider", "form");
    private static final Set<String> FIELD_TYPES = Set.of(
            "text", "textarea", "number", "email", "url",
            "select", "multiselect", "radio", "checkbox", "checkboxes",
            "date", "time", "datetime");
    private static final Set<String> OPTION_FIELD_TYPES = Set.of("select", "multiselect", "radio", "checkboxes");
    private static final Set<String> SHORT_CHOICE_FIELD_TYPES = Set.of("radio", "checkboxes");
    private static final Set<String> TONES = Set.of("neutral", "info", "success", "warning", "danger");
    private static final Pattern FIELD_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,39}");
    private static final String BLOCKS_ERROR = "card.blocks is required (1-" + MAX_BLOCKS + " items)";

    /** @return null when the card is valid; otherwise a readable reason the card was rejected */
    public static String validate(String cardJson) {
        if (cardJson == null || cardJson.isBlank()) return "card must be a JSON object";
        Object parsed;
        try {
            parsed = JsonUtil.fromJson(Map.class, cardJson);
        } catch (RuntimeException e) {
            return "card must be a JSON object";
        }
        if (!(parsed instanceof Map<?, ?> card)) return "card must be a JSON object";
        var version = card.get("schema_version");
        if (version != null && (!(version instanceof Number number) || number.intValue() != 1)) {
            return "unsupported card schema_version: " + version;
        }
        if (card.get("title") != null && string(card.get("title")) == null) return "card.title must be a string";
        if (!(card.get("blocks") instanceof List<?> blocks) || blocks.isEmpty()) return BLOCKS_ERROR;
        if (blocks.size() > MAX_BLOCKS) return BLOCKS_ERROR;
        var formCount = 0;
        for (int i = 0; i < blocks.size(); i++) {
            var error = validateBlock(i, blocks.get(i));
            if (error != null) return error;
            if (blocks.get(i) instanceof Map<?, ?> block && "form".equals(block.get("type"))) formCount++;
        }
        if (formCount > 1) return "card supports at most one form block";
        return null;
    }

    private static String validateBlock(int index, Object raw) {
        if (!(raw instanceof Map<?, ?> block)) return error(index, "each block must be a JSON object");
        var type = string(block.get("type"));
        if (type == null || type.isBlank()) return error(index, "type is required");
        if (!BLOCK_TYPES.contains(type)) return error(index, "unknown block type \"" + type + "\"");
        var toneError = validateTone(index, block.get("tone"));
        if (toneError != null) return toneError;
        if ("text".equals(type)) return validateText(index, block);
        if ("key_values".equals(type)) return validateKeyValues(index, block);
        if ("table".equals(type)) return validateTable(index, block);
        if ("image".equals(type)) return validateImage(index, block);
        if ("actions".equals(type)) return validateActions(index, block);
        if ("form".equals(type)) return validateForm(index, block);
        return null;
    }

    private static String validateText(int index, Map<?, ?> block) {
        var text = string(block.get("text"));
        if (text == null || text.isBlank()) return error(index, "text requires a non-blank \"text\"");
        return null;
    }

    private static String validateKeyValues(int index, Map<?, ?> block) {
        if (!(block.get("items") instanceof List<?> items) || items.isEmpty()) return error(index, "key_values requires a non-empty \"items\" array");
        for (var item : items) {
            if (!(item instanceof Map<?, ?> entry) || blank(string(entry.get("label"))) || entry.get("value") == null) {
                return error(index, "key_values items must be objects with \"label\" and \"value\"");
            }
            var toneError = validateTone(index, entry.get("tone"));
            if (toneError != null) return toneError;
        }
        return null;
    }

    private static String validateTable(int index, Map<?, ?> block) {
        if (!(block.get("columns") instanceof List<?> columns) || columns.isEmpty()) return error(index, "table requires a non-empty \"columns\" array");
        if (columns.size() > MAX_TABLE_COLUMNS) return error(index, "table supports at most " + MAX_TABLE_COLUMNS + " columns");
        for (var column : columns) {
            if (!(column instanceof Map<?, ?> entry) || blank(string(entry.get("key"))) || blank(string(entry.get("label")))) {
                return error(index, "table columns must be objects with \"key\" and \"label\"");
            }
        }
        if (!(block.get("rows") instanceof List<?> rows)) return error(index, "table requires a \"rows\" array");
        for (var row : rows) {
            if (!(row instanceof Map<?, ?>)) return error(index, "table rows must be JSON objects");
        }
        if (block.get("caption") != null && string(block.get("caption")) == null) return error(index, "table caption must be a string");
        return null;
    }

    private static String validateImage(int index, Map<?, ?> block) {
        if (blank(string(block.get("url")))) return error(index, "image requires a non-blank \"url\"");
        return null;
    }

    private static String validateActions(int index, Map<?, ?> block) {
        if (!(block.get("options") instanceof List<?> options) || options.isEmpty() || options.size() > MAX_ACTIONS) {
            return error(index, "actions requires 1-" + MAX_ACTIONS + " options");
        }
        for (var option : options) {
            if (!(option instanceof Map<?, ?> entry) || blank(string(entry.get("label")))) return error(index, "each option needs a non-blank \"label\"");
            if (entry.get("value") != null && string(entry.get("value")) == null) return error(index, "option \"value\" must be a string");
            if (entry.get("description") != null && string(entry.get("description")) == null) return error(index, "option \"description\" must be a string");
            var urlError = validateActionUrl(index, entry.get("url"));
            if (urlError != null) return urlError;
            var toneError = validateTone(index, entry.get("tone"));
            if (toneError != null) return toneError;
        }
        return null;
    }

    private static String validateForm(int index, Map<?, ?> block) {
        if (block.get("submit_label") != null && blank(string(block.get("submit_label")))) {
            return error(index, "form \"submit_label\" must be a non-blank string");
        }
        if (!(block.get("fields") instanceof List<?> fields) || fields.isEmpty() || fields.size() > MAX_FORM_FIELDS) {
            return error(index, "form requires 1-" + MAX_FORM_FIELDS + " fields");
        }
        var names = new HashSet<String>();
        for (var raw : fields) {
            if (!(raw instanceof Map<?, ?> field)) return error(index, "form fields must be JSON objects");
            var name = string(field.get("name"));
            if (name == null || !FIELD_NAME.matcher(name).matches()) {
                return error(index, "form field \"name\" must be an identifier (letters, digits, underscore; max 40 chars)");
            }
            if (!names.add(name)) return error(index, "duplicate form field name \"" + name + "\"");
            if (blank(string(field.get("label")))) return error(index, "form field \"" + name + "\" requires a non-blank \"label\"");
            var type = field.get("type");
            if (type != null && (string(type) == null || !FIELD_TYPES.contains(type))) {
                return error(index, "form field \"" + name + "\" has unsupported type \"" + type + "\"");
            }
            var typeName = type == null ? "text" : string(type);
            if (field.get("required") != null && !(field.get("required") instanceof Boolean)) {
                return error(index, "form field \"" + name + "\" \"required\" must be a boolean");
            }
            if (field.get("placeholder") != null && string(field.get("placeholder")) == null) {
                return error(index, "form field \"" + name + "\" \"placeholder\" must be a string");
            }
            if (field.get("hint") != null && string(field.get("hint")) == null) {
                return error(index, "form field \"" + name + "\" \"hint\" must be a string");
            }
            if (OPTION_FIELD_TYPES.contains(typeName)) {
                var maxOptions = SHORT_CHOICE_FIELD_TYPES.contains(typeName) ? MAX_CHOICE_OPTIONS : MAX_SELECT_OPTIONS;
                var optionError = validateOptions(index, name, field.get("options"), maxOptions);
                if (optionError != null) return optionError;
            }
            if ("number".equals(typeName)) {
                var min = field.get("min");
                var max = field.get("max");
                if (min != null && !(min instanceof Number)) return error(index, "form field \"" + name + "\" \"min\" must be a number");
                if (max != null && !(max instanceof Number)) return error(index, "form field \"" + name + "\" \"max\" must be a number");
                if (min != null && max != null && ((Number) min).doubleValue() > ((Number) max).doubleValue()) {
                    return error(index, "form field \"" + name + "\" \"min\" must not exceed \"max\"");
                }
            }
        }
        return null;
    }

    private static String validateOptions(int index, String name, Object raw, int max) {
        if (!(raw instanceof List<?> options) || options.isEmpty() || options.size() > max) {
            return error(index, "form field \"" + name + "\" requires 1-" + max + " options");
        }
        var values = new HashSet<String>();
        for (var option : options) {
            String value;
            if (option instanceof String text) {
                value = text;
            } else if (option instanceof Map<?, ?> object) {
                var label = string(object.get("label"));
                if (blank(label)) return error(index, "form option \"label\" must be a non-blank string");
                var specified = object.get("value");
                value = specified == null ? label : string(specified);
                if (blank(value)) return error(index, "form option \"value\" must be a non-blank string");
            } else {
                return error(index, "form options must be strings or {label, value} objects");
            }
            if (blank(value)) return error(index, "form options must be non-blank");
            if (!values.add(value)) return error(index, "duplicate option value \"" + value + "\" in form field \"" + name + "\"");
        }
        return null;
    }

    private static String validateActionUrl(int index, Object url) {
        if (url == null) return null;
        var value = string(url);
        if (value == null || value.isBlank()
            || !(value.startsWith("http://") || value.startsWith("https://") || value.startsWith("/api/"))) {
            return error(index, "option \"url\" must be an http(s) URL or a platform /api/ link");
        }
        return null;
    }

    private static String validateTone(int index, Object tone) {
        if (tone == null) return null;
        var value = string(tone);
        if (value == null || !TONES.contains(value)) return error(index, "unsupported tone: " + tone);
        return null;
    }

    private static String error(int index, String reason) {
        return "card.blocks[" + index + "]: " + reason;
    }

    private static String string(Object value) {
        return value instanceof String text ? text : null;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private RichCards() {
    }
}
