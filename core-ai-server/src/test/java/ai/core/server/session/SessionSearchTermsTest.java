package ai.core.server.session;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionSearchTermsTest {
    @Test
    void splitsLatinWordsAndDropsStopwordsAndShortTokens() {
        assertEquals(List.of("terraform", "aks", "deploy"), SessionSearchTerms.terms("The terraform aks deploy for this"));
    }

    @Test
    void splitsWordsOnPunctuation() {
        assertEquals(List.of("core", "ai", "kubernetes", "v1"), SessionSearchTerms.terms("core-ai, Kubernetes; v1.2"));
    }

    @Test
    void normalizesFullWidthCharacters() {
        assertEquals(List.of("kestrel"), SessionSearchTerms.terms("ｋｅｓｔｒｅｌ"));
    }

    @Test
    void reducesChineseRunsToBigrams() {
        assertEquals(List.of("知识", "识库", "库设", "设计"), SessionSearchTerms.terms("知识库设计"));
    }

    @Test
    void keepsSingleChineseCharacters() {
        assertEquals(List.of("钱"), SessionSearchTerms.terms("钱"));
    }

    @Test
    void mergesLatinAndChineseTerms() {
        assertEquals(List.of("nas", "群晖", "晖的", "的备", "备份"), SessionSearchTerms.terms("nas 群晖的备份"));
    }

    @Test
    void capsTheNumberOfTerms() {
        var query = "alpha bravo charlie delta echo foxtrot golf hotel india juliet kilo lima mike november oscar papa quebec";
        var terms = SessionSearchTerms.terms(query);

        assertEquals(16, terms.size());
        assertEquals("alpha", terms.getFirst());
        assertEquals("papa", terms.getLast());
    }

    @Test
    void blankQueryHasNoTerms() {
        assertEquals(List.of(), SessionSearchTerms.terms(null));
        assertEquals(List.of(), SessionSearchTerms.terms("   "));
        assertEquals(List.of(), SessionSearchTerms.terms("the and for"));
    }

    @Test
    void matchCountCountsDistinctTermsPresent() {
        var terms = List.of("terraform", "aks", "deploy");

        assertEquals(2, SessionSearchTerms.matchCount("Terraform apply on the aks cluster", terms));
        assertEquals(0, SessionSearchTerms.matchCount(null, terms));
        assertEquals(0, SessionSearchTerms.matchCount("terraform", List.of()));
    }

    @Test
    void snippetIsCenteredOnTheMatch() {
        var content = "x".repeat(200) + "postgres upgrade" + "y".repeat(200);

        var snippet = SessionSearchTerms.snippet(content, List.of("postgres"), 80);

        assertTrue(snippet.startsWith("…"));
        assertTrue(snippet.endsWith("…"));
        assertTrue(snippet.contains("postgres upgrade"));
        assertEquals(2 + 2 * 80, snippet.length());
    }

    @Test
    void snippetFallsBackToTheHeadWhenNothingMatches() {
        var snippet = SessionSearchTerms.snippet("a".repeat(300), List.of("missing"), 80);

        assertTrue(snippet.startsWith("a"));
        assertFalse(snippet.startsWith("…"));
        assertEquals(161, snippet.length());
    }

    @Test
    void snippetCollapsesWhitespace() {
        var snippet = SessionSearchTerms.snippet("deploy\n\n  the\ttenant", List.of("tenant"), 80);

        assertEquals("deploy the tenant", snippet);
    }
}
