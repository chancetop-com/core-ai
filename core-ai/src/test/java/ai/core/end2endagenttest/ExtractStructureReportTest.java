package ai.core.end2endagenttest;

import ai.core.IntegrationTest;
import ai.core.llm.LLMProviders;
import ai.core.utils.JsonUtil;
import core.framework.api.json.Property;
import core.framework.api.validate.NotBlank;
import core.framework.api.validate.NotNull;
import core.framework.inject.Inject;
import core.framework.util.ClasspathResources;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * @author stephen
 */
@Disabled
class ExtractStructureReportTest extends IntegrationTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(ExtractStructureReportTest.class);
    @Inject
    LLMProviders llmProviders;

    @Test
    void test() {
        var report = ClasspathResources.text("reports/zandy_final_workflow_report_2026-01-30.md");
        var rst = llmProviders.getProvider().completionFormat("""
                Extract the structure information from the following report in JSON format.
                Do not edit any content, just extract the structure.
                """, report, "gpt-5.1", ReportGeneratedMessage.class);
        LOGGER.info(JsonUtil.toJson(rst));
    }

    public static class RecommendationAction {
        @NotNull
        @Property(name = "id")
        public String id;

        @NotNull
        @Property(name = "title")
        public String title;

        @Property(name = "details")
        public List<String> details;
    }

    public static class ReportGeneratedMessage {
        @NotNull
        @NotBlank
        @Property(name = "name")
        public String name;

        @NotNull
        @Property(name = "sections")
        public List<ReportSectionL1> sections = List.of();

        @NotNull
        @Property(name = "low_hanging_fruit_actions")
        public List<RecommendationAction> lowHangingFruitActions = List.of();

        public static class ReportSectionL1 {
            @NotNull
            @NotBlank
            @Property(name = "id")
            public String id;

            @Property(name = "title")
            public String title;

            @Property(name = "content")
            public String content;

            @Property(name = "sections")
            public List<ReportSectionL2> sections;
        }

        public static class ReportSectionL2 {
            @NotNull
            @NotBlank
            @Property(name = "id")
            public String id;

            @Property(name = "title")
            public String title;

            @Property(name = "content")
            public String content;

            @Property(name = "sections")
            public List<ReportSectionL3> sections;
        }

        public static class ReportSectionL3 {
            @NotNull
            @NotBlank
            @Property(name = "id")
            public String id;

            @Property(name = "title")
            public String title;

            @Property(name = "content")
            public String content;
        }
    }
}
