package ai.core.tool;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolSearchScorerTest {
    @Test
    void tokenizeLowercasesAndDropsBlanks() {
        assertEquals(List.of("google", "gbp"), ToolSearchScorer.tokenize("  Google   Gbp "));
        assertTrue(ToolSearchScorer.tokenize(null).isEmpty());
        assertTrue(ToolSearchScorer.tokenize("   ").isEmpty());
    }

    @Test
    void serverNameHitIsBrandLevelAndOutweighsToolNameHit() {
        var brandContains = ToolSearchScorer.match("get_reviews", null, "google-gbp", "google");
        var brandExact = ToolSearchScorer.match("get_reviews", null, "gbp", "gbp");
        var toolNameHit = ToolSearchScorer.match("google_keyword_data", null, "dataforseo", "google");

        assertEquals(ToolSearchScorer.SERVER_NAME_CONTAINS_TOKEN, brandContains.score());
        assertEquals(ToolSearchScorer.SERVER_NAME_EQUALS_TOKEN, brandExact.score());
        assertEquals(ToolSearchScorer.TOOL_NAME_CONTAINS_TOKEN, toolNameHit.score());
        assertTrue(brandContains.score() > toolNameHit.score(), "brand layer must outweigh tool-name hits");
        assertTrue(brandExact.score() > brandContains.score(), "exact server name beats substring");
        assertTrue(brandContains.allTokensHit());
    }

    @Test
    void exactToolNameEqualsQueryGetsBonus() {
        var match = ToolSearchScorer.match("create", "A shortcut tool", null, "create");
        assertEquals(ToolSearchScorer.NAME_EQUALS_QUERY + ToolSearchScorer.TOOL_NAME_CONTAINS_TOKEN, match.score());
        assertTrue(match.allTokensHit());
    }

    @Test
    void descriptionHitScoresLowest() {
        var match = ToolSearchScorer.match("tool_a", "Checks inventory levels", null, "inventory");
        assertEquals(ToolSearchScorer.DESCRIPTION_CONTAINS_TOKEN, match.score());
        assertTrue(match.anyTokenHit());
    }

    @Test
    void allTokensHitRequiresEveryTokenToMatchSomeField() {
        var match = ToolSearchScorer.match("get_reviews", "List reviews", "google-gbp", "reviews console");
        assertFalse(match.allTokensHit(), "console hits nothing, AND rule must fail");
        assertTrue(match.anyTokenHit(), "reviews still hits, OR rule must pass");
    }

    @Test
    void noHitScoresZeroAndFailsBothRules() {
        var match = ToolSearchScorer.match("get_reviews", "List reviews", "google-gbp", "xyz");
        assertEquals(0, match.score());
        assertFalse(match.allTokensHit());
        assertFalse(match.anyTokenHit());
    }

    @Test
    void nullNameAndDescriptionAreSafe() {
        var match = ToolSearchScorer.match(null, null, "google-gbp", "google");
        assertTrue(match.allTokensHit());
        assertEquals(ToolSearchScorer.SERVER_NAME_CONTAINS_TOKEN, match.score());
    }

    @Test
    void emptyQueryMatchesNothingToScore() {
        var match = ToolSearchScorer.match("tool", "desc", null, "");
        assertEquals(0, match.score());
        assertFalse(match.anyTokenHit());
    }

    @Test
    void serverNameScoreSumsTokenHits() {
        assertEquals(ToolSearchScorer.SERVER_NAME_CONTAINS_TOKEN, ToolSearchScorer.serverNameScore("google-gbp", List.of("google")));
        assertEquals(ToolSearchScorer.SERVER_NAME_EQUALS_TOKEN, ToolSearchScorer.serverNameScore("gbp", List.of("gbp")));
        assertEquals(2 * ToolSearchScorer.SERVER_NAME_CONTAINS_TOKEN, ToolSearchScorer.serverNameScore("google-ads", List.of("google", "ads")));
        assertEquals(0, ToolSearchScorer.serverNameScore("dataforseo", List.of("google")));
        assertEquals(0, ToolSearchScorer.serverNameScore(null, List.of("google")));
    }
}
