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
              {"type":"actions","options":[{"label":"继续","value":"continue"},{"label":"预览"}]},
              {"type":"form","submit_label":"开始","fields":[
                {"name":"full_name","label":"姓名","type":"text","required":true},
                {"name":"company_size","label":"规模","type":"select","options":["1-50","51-200"]},
                {"name":"notes","label":"需求","type":"textarea","placeholder":"简要描述"},
                {"name":"budget","label":"预算","type":"number"},
                {"name":"agree","label":"同意条款","type":"checkbox","required":true}
              ]}
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
    void acceptsAFormWithEveryFieldTypeAndATextBlock() {
        var card = """
            {"title":"Tell us about you","blocks":[
              {"type":"text","text":"These details decide the next step."},
              {"type":"form","submit_label":"Send","fields":[
                {"name":"name","label":"Name","type":"text","required":true},
                {"name":"bio","label":"Bio","type":"textarea","hint":"Anything we should know"},
                {"name":"seats","label":"Seats","type":"number","min":1,"max":500},
                {"name":"email","label":"Email","type":"email"},
                {"name":"site","label":"Site","type":"url"},
                {"name":"plan","label":"Plan","type":"select","options":["Basic","Pro"]},
                {"name":"topics","label":"Topics","type":"multiselect","options":["Billing","Support"]},
                {"name":"slot","label":"Slot","type":"radio","options":["AM","PM"]},
                {"name":"newsletter","label":"Newsletter","type":"checkbox"},
                {"name":"channels","label":"Channels","type":"checkboxes","options":["Email","SMS"]},
                {"name":"start","label":"Start","type":"date"},
                {"name":"at","label":"At","type":"time"},
                {"name":"when","label":"When","type":"datetime"}
              ]}
            ]}
            """;

        assertNull(RichCards.validate(card));
    }

    @Test
    void acceptsOptionsAsLabelValueObjects() {
        var card = """
            {"blocks":[{"type":"form","fields":[
              {"name":"plan","label":"Plan","type":"select","options":[
                {"label":"基础版","value":"basic"},
                {"label":"专业版","value":"pro"}
              ]}
            ]}]}
            """;

        assertNull(RichCards.validate(card));
    }

    @Test
    void rejectsFormShapeErrors() {
        assertNotNull(RichCards.validate("{\"blocks\":[{\"type\":\"form\"}]}"));
        assertNotNull(RichCards.validate("{\"blocks\":[{\"type\":\"form\",\"fields\":[]}]}"));
        assertNotNull(RichCards.validate("{\"blocks\":[{\"type\":\"form\",\"fields\":[{\"label\":\"Name\"}]}]}"));
        assertNotNull(RichCards.validate("{\"blocks\":[{\"type\":\"form\",\"fields\":[{\"name\":\"1name\",\"label\":\"Name\"}]}]}"));
        assertNotNull(RichCards.validate("{\"blocks\":[{\"type\":\"form\",\"fields\":[{\"name\":\"name\",\"label\":\"Name\"},{\"name\":\"name\",\"label\":\"Again\"}]}]}"));
        assertNotNull(RichCards.validate("{\"blocks\":[{\"type\":\"form\",\"fields\":[{\"name\":\"name\"}]}]}"));
        assertNotNull(RichCards.validate("{\"blocks\":[{\"type\":\"form\",\"fields\":[{\"name\":\"name\",\"label\":\"Name\",\"type\":\"file\"}]}]}"));
        assertNotNull(RichCards.validate("{\"blocks\":[{\"type\":\"form\",\"fields\":[{\"name\":\"name\",\"label\":\"Name\",\"required\":\"yes\"}]}]}"));
        assertNotNull(RichCards.validate("{\"blocks\":[{\"type\":\"form\",\"fields\":[{\"name\":\"plan\",\"label\":\"Plan\",\"type\":\"select\"}]}]}"));
        assertNotNull(RichCards.validate("{\"blocks\":[{\"type\":\"form\",\"fields\":[{\"name\":\"plan\",\"label\":\"Plan\",\"type\":\"select\",\"options\":[\"Basic\",\"\"]}]}]}"));
        assertNotNull(RichCards.validate("{\"blocks\":[{\"type\":\"form\",\"submit_label\":\" \",\"fields\":[{\"name\":\"name\",\"label\":\"Name\"}]}]}"));
        assertNotNull(RichCards.validate("{\"blocks\":[{\"type\":\"form\",\"fields\":[{\"name\":\"x\",\"label\":\"X\",\"type\":\"radio\",\"options\":[\"1\",\"2\",\"3\",\"4\",\"5\",\"6\",\"7\",\"8\",\"9\",\"10\",\"11\"]}]}]}"));
        assertNotNull(RichCards.validate("{\"blocks\":[{\"type\":\"form\",\"fields\":[{\"name\":\"x\",\"label\":\"X\",\"type\":\"select\",\"options\":[{\"value\":\"a\"}]}]}]}"));
        assertNotNull(RichCards.validate("{\"blocks\":[{\"type\":\"form\",\"fields\":[{\"name\":\"x\",\"label\":\"X\",\"type\":\"select\",\"options\":[\"a\",\"a\"]}]}]}"));
        assertNotNull(RichCards.validate("{\"blocks\":[{\"type\":\"form\",\"fields\":[{\"name\":\"x\",\"label\":\"X\",\"type\":\"number\",\"min\":5,\"max\":1}]}]}"));
        assertNotNull(RichCards.validate("{\"blocks\":[{\"type\":\"form\",\"fields\":[{\"name\":\"x\",\"label\":\"X\",\"type\":\"number\",\"min\":\"1\"}]}]}"));
        assertNotNull(RichCards.validate("{\"blocks\":[{\"type\":\"form\",\"fields\":[{\"name\":\"x\",\"label\":\"X\",\"hint\":1}]}]}"));
    }

    @Test
    void rejectsMoreThanOneFormPerCard() {
        var reason = RichCards.validate("""
            {"blocks":[
              {"type":"form","fields":[{"name":"a","label":"A"}]},
              {"type":"form","fields":[{"name":"b","label":"B"}]}
            ]}
            """);

        assertNotNull(reason);
        assertTrue(reason.contains("at most one form"), reason);
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
