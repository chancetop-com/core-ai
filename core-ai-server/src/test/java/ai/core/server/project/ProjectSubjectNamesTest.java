package ai.core.server.project;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * @author core-ai
 */
class ProjectSubjectNamesTest {
    @Test
    void collapsesCaseSpacingPunctuationAndAmpersand() {
        assertEquals("acme bakery", ProjectSubjectNames.normalize("  ACME   Bakery "));
        assertEquals("acme and sons", ProjectSubjectNames.normalize("Acme & Sons"));
        assertEquals("hilton", ProjectSubjectNames.normalize("Hilton, Inc."));
    }

    @Test
    void stripsTrailingGenericSuffixes() {
        assertEquals("acme", ProjectSubjectNames.normalize("Acme LLC"));
        assertEquals("acme", ProjectSubjectNames.normalize("Acme Spa Inc"));
        assertEquals("acme", ProjectSubjectNames.normalize("acme store"));
        assertEquals("spa", ProjectSubjectNames.normalize("Spa"));   // a bare generic word is a name, not a suffix
    }

    @Test
    void keepsNonGenericWords() {
        assertEquals("acme hotels", ProjectSubjectNames.normalize("Acme Hotels"));
        assertNull(ProjectSubjectNames.normalize(null));
    }
}
