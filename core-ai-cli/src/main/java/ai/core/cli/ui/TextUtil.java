package ai.core.cli.ui;

/**
 * @author xander
 */
public final class TextUtil {

    public static String truncateByDisplayWidth(String text, int maxColumns) {
        int width = 0;
        int pos = 0;
        while (pos < text.length()) {
            int cp = text.codePointAt(pos);
            int charWidth = isWideChar(cp) ? 2 : 1;
            if (width + charWidth > maxColumns) {
                return text.substring(0, pos) + "...";
            }
            width += charWidth;
            pos += Character.charCount(cp);
        }
        return text;
    }

    private static boolean isWideChar(int cp) {
        return cp >= 0x1100 && cp <= 0x115F
                || cp >= 0x2E80 && cp <= 0x9FFF
                || cp >= 0xAC00 && cp <= 0xD7AF
                || cp >= 0xF900 && cp <= 0xFAFF
                || cp >= 0xFE10 && cp <= 0xFE6F
                || cp >= 0xFF01 && cp <= 0xFF60
                || cp >= 0xFFE0 && cp <= 0xFFE6
                || cp >= 0x20000 && cp <= 0x2FA1F;
    }

    private TextUtil() {
    }
}
