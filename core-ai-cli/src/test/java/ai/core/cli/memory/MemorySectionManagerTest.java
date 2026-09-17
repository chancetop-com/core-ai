package ai.core.cli.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MemorySectionManagerTest {

    @Test
    void replaceMemoriesSectionKeepsDollarSignsLiteral() {
        String text = "before\n<memories>\nold\n</memories>\nafter";
        String replacement = "<memories>\n`$merge`, `$in`, `{field:{$exists:false}}`\n</memories>";

        String result = MemorySectionManager.replaceMemoriesSection(text, replacement);

        assertEquals("before\n" + replacement + "\nafter", result);
    }

    @Test
    void replaceMemoriesSectionReplacesMultiLineSection() {
        String text = "head\n<memories>\nline1\nline2\n</memories>\ntail";
        String replacement = "<memories>\n(empty)\n</memories>";

        String result = MemorySectionManager.replaceMemoriesSection(text, replacement);

        assertEquals("head\n" + replacement + "\ntail", result);
    }

    @Test
    void replaceMemoriesSectionLeavesTextWithoutSectionUnchanged() {
        String text = "no memory section here";

        assertEquals(text, MemorySectionManager.replaceMemoriesSection(text, "<memories>x</memories>"));
    }
}
