package ai.core.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class RichCardsTest {
    @Test
    void acceptsEveryBlockOfTheV1Vocabulary() {
        var card = """
            {"schema_version":1,"title":"Menu · 12 items","blocks":[
              {"type":"text","text":"识别到 12 个菜品。","tone":"info"},
              {"type":"key_values","items":[{"label":"Business type","value":"Thai"},{"label":"Source","value":"photo"}]},
              {"type":"table","columns":[{"key":"name","label":"Item"},{"key":"price","label":"Price"}],
               "rows":[{"name":"Pad Thai","price":"12.90"}],"caption":"top items"},
              {"type":"image","url":"/api/files/f1/content","alt":"preview"},
              {"type":"divider"},
              {"type":"actions","options":[{"label":"继续","value":"continue"},{"label":"预览"}]}
            ]}
            """;

        assertNull(RichCards.validate(card));
    }

    @Test
    void acceptsACardWithoutOptionalFields() {
        assertNull(RichCards.validate("{\"blocks\":[{\"type\":\"divider\"}]}"));
    }

    @Test
    void rejectsNonJsonAndNonObjectCards() {
        assertNotNull(RichCards.validate(null));
        assertNotNull(RichCards.validate("not json"));
        assertNotNull(RichCards.validate("[1,2]"));
    }

    @Test
    void rejectsAnUnsupportedSchemaVersion() {
        var reason = RichCards.validate("{\"schema_version\":2,\"blocks\":[{\"type\":\"divider\"}]}");

        assertNotNull(reason);
        assertTrue(reason.contains("schema_version"), reason);
    }

    @Test
    void rejectsMissingOrEmptyBlocks() {
        assertNotNull(RichCards.validate("{}"));
        assertNotNull(RichCards.validate("{\"blocks\":[]}"));
    }

    @Test
    void rejectsAnUnknownBlockType() {
        var reason = RichCards.validate("{\"blocks\":[{\"type\":\"divider\"},{\"type\":\"chart\"}]}");

        assertNotNull(reason);
        assertTrue(reason.contains("card.blocks[1]"), reason);
        assertTrue(reason.contains("unknown block type"), reason);
    }

    @Test
    void rejectsAnUnsupportedTone() {
        assertNotNull(RichCards.validate("{\"blocks\":[{\"type\":\"text\",\"text\":\"hi\",\"tone\":\"loud\"}]}"));
    }

    @Test
    void validatesActionUrls() {
        var valid = """
            {"blocks":[{"type":"actions","options":[
              {"label":"FAQ","url":"https://example.com/faq"},
              {"label":"Page","url":"/api/public/artifacts/t1/content"}]}]}
            """;
        assertNull(RichCards.validate(valid));

        assertNotNull(RichCards.validate("{\"blocks\":[{\"type\":\"actions\",\"options\":[{\"label\":\"x\",\"url\":\"javascript:alert(1)\"}]}]}"));
        assertNotNull(RichCards.validate("{\"blocks\":[{\"type\":\"actions\",\"options\":[{\"label\":\"x\",\"url\":\"data:text/html,x\"}]}]}"));
        assertNotNull(RichCards.validate("{\"blocks\":[{\"type\":\"actions\",\"options\":[{\"label\":\"x\",\"url\":123}]}]}"));
    }

    @Test
    void rejectsBlockSpecificShapeErrors() {
        assertNotNull(RichCards.validate("{\"blocks\":[{\"type\":\"text\"}]}"));
        assertNotNull(RichCards.validate("{\"blocks\":[{\"type\":\"text\",\"text\":\"  \"}]}"));
        assertNotNull(RichCards.validate("{\"blocks\":[{\"type\":\"key_values\",\"items\":[]}]}"));
        assertNotNull(RichCards.validate("{\"blocks\":[{\"type\":\"key_values\",\"items\":[{\"label\":\"a\"}]}]}"));
        assertNotNull(RichCards.validate("{\"blocks\":[{\"type\":\"table\",\"rows\":[]}]}"));
        assertNotNull(RichCards.validate("{\"blocks\":[{\"type\":\"table\",\"columns\":[{\"key\":\"name\"}],\"rows\":[]}]}"));
        assertNotNull(RichCards.validate("{\"blocks\":[{\"type\":\"table\",\"columns\":[{\"key\":\"a\",\"label\":\"A\"}],\"rows\":[\"not-an-object\"]}]}"));
        assertNotNull(RichCards.validate("{\"blocks\":[{\"type\":\"image\"}]}"));
        assertNotNull(RichCards.validate("{\"blocks\":[{\"type\":\"actions\",\"options\":[]}]}"));
        assertNotNull(RichCards.validate("{\"blocks\":[{\"type\":\"actions\",\"options\":[{\"label\":\"a\"},{\"label\":\"b\"},{\"label\":\"c\"},{\"label\":\"d\"},{\"label\":\"e\"},{\"label\":\"f\"},{\"label\":\"g\"}]}]}"));
        assertNotNull(RichCards.validate("{\"blocks\":[{\"type\":\"actions\",\"options\":[{\"value\":\"x\"}]}]}"));
    }
}
