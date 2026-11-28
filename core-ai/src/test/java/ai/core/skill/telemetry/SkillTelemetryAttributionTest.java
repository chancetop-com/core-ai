package ai.core.skill.telemetry;

import ai.core.agent.ExecutionContext;
import ai.core.llm.domain.FunctionCall;
import ai.core.skill.SkillCapability;
import ai.core.skill.SkillLoadException;
import ai.core.skill.SkillMetadata;
import ai.core.skill.SkillProvider;
import ai.core.skill.SkillRegistry;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallResult;
import ai.core.tool.tools.PythonScriptTool;
import ai.core.tool.tools.ReadSkillResourceTool;
import ai.core.tool.tools.ShellCommandTool;
import ai.core.tool.tools.SkillTool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for skill runtime telemetry collection and deterministic script/capability attribution.
 *
 * @author stephen
 */
class SkillTelemetryAttributionTest {
    @Test
    void unrelatedBashErrorCountsAsUnattributedNotMountedSkill() {
        var collector = new SkillTelemetryCollector();
        var mounted = List.of(
                SkillMetadata.builder("deploy", "Deploy helper", "/skills/deploy/SKILL.md")
                        .skillId("skill-deploy")
                        .namespace("team")
                        .digest("sha-deploy")
                        .skillDir("/workspace/.core-ai/skills/deploy")
                        .build()
        );
        var lifecycle = new SkillTelemetryLifecycle(() -> mounted, null, collector, null);

        var unrelatedCall = FunctionCall.of("c1", "function", ShellCommandTool.TOOL_NAME,
                "{\"command\":\"cat /tmp/not-a-skill.txt\"}");
        lifecycle.afterTool(unrelatedCall, ExecutionContext.empty(), ToolCallResult.failed("exit code 1"));

        assertEquals(1L, collector.unattributedScriptErrorCount());
        assertTrue(collector.drain().isEmpty());
    }

    @Test
    void scriptPathAttributionMatchesExactSkillDirectoryAndAvoidsPrefixCollision() {
        var collector = new SkillTelemetryCollector();
        var demo = SkillMetadata.builder("demo", "Demo skill", "/skills/demo/SKILL.md")
                .skillId("skill-demo")
                .namespace("team")
                .digest("sha-demo")
                .skillDir("/home/dev/.core-ai/skills/demo")
                .build();
        var demoExtra = SkillMetadata.builder("demo-extra", "Demo extra", "/skills/demo-extra/SKILL.md")
                .skillId("skill-demo-extra")
                .namespace("team")
                .digest("sha-extra")
                .skillDir("/home/dev/.core-ai/skills/demo-extra")
                .build();
        var lifecycle = new SkillTelemetryLifecycle(() -> List.of(demo, demoExtra), null, collector, null);

        var sandboxCall = FunctionCall.of("c1", "function", ShellCommandTool.TOOL_NAME,
                "{\"command\":\"bash /skill/demo/scripts/run.sh\"}");
        lifecycle.afterTool(sandboxCall, ExecutionContext.empty(), ToolCallResult.failed("boom"));

        var pyCall = FunctionCall.of("c2", "function", PythonScriptTool.TOOL_NAME,
                "{\"script_path\":\"/home/dev/.core-ai/skills/demo/scripts/check.py\"}");
        lifecycle.afterTool(pyCall, ExecutionContext.empty(), ToolCallResult.completed("ok"));

        assertEquals(0L, collector.unattributedScriptErrorCount());
        var samples = collector.drain();
        assertEquals(1, samples.size());
        assertEquals("skill-demo", samples.getFirst().identity().skillId());
        assertEquals(2L, samples.getFirst().attributedScriptCallsDelta());
        assertEquals(1L, samples.getFirst().attributedScriptErrorsDelta());
    }

    @Test
    void capabilityCallAttributionRoutesToContributingSkillsOnly() {
        var collector = new SkillTelemetryCollector();
        var skill = SkillMetadata.builder("crm", "CRM skill", "/skills/crm/SKILL.md")
                .skillId("skill-crm")
                .namespace("sales")
                .digest("sha-crm")
                .build();
        var stubTool = new StubTool("mcp_crm_lookup");
        var ctx = ExecutionContext.empty();
        ctx.setSkillCapabilities(List.of(
                new SkillCapability("skill-crm", "sales/crm", "mcp", "mcp:crm:lookup", "crm/lookup", "crm", stubTool)
        ));

        var lifecycle = new SkillTelemetryLifecycle(() -> List.of(skill), null, collector, null);
        var call = FunctionCall.of("c1", "function", "mcp_crm_lookup", "{}");
        lifecycle.afterTool(call, ctx, ToolCallResult.failed("timeout"));

        var samples = collector.drain();
        assertEquals(1, samples.size());
        assertEquals("skill-crm", samples.getFirst().identity().skillId());
        assertEquals(1L, samples.getFirst().attributedScriptCallsDelta());
        assertEquals(1L, samples.getFirst().attributedScriptErrorsDelta());
        assertEquals(0L, collector.unattributedScriptErrorCount());
    }

    @Test
    void skillToolAndReadResourceRecordInvokeResourceReadAndMaterializeFail() {
        var collector = new SkillTelemetryCollector();
        var okSkill = SkillMetadata.builder("guide", "Guide", "/skills/guide/SKILL.md")
                .namespace("local")
                .digest("sha-guide")
                .content("# Guide")
                .resources(List.of("references/a.md"))
                .build();
        var brokenSkill = SkillMetadata.builder("broken", "Broken", "/skills/broken/SKILL.md")
                .namespace("local")
                .digest("sha-broken")
                .build();

        var registry = new SkillRegistry();
        registry.addProvider(new StubSkillProvider(List.of(okSkill, brokenSkill)));

        var useSkill = SkillTool.builder().registry(registry).telemetrySink(collector).build();
        var readResource = ReadSkillResourceTool.builder().registry(registry).telemetrySink(collector).build();

        assertTrue(useSkill.execute("{\"name\":\"local/guide\"}").isCompleted());
        assertTrue(readResource.execute("{\"name\":\"local/guide\",\"path\":\"references/a.md\"}").isCompleted());
        assertTrue(useSkill.execute("{\"name\":\"local/broken\"}").isFailed());

        var samples = collector.drain();
        assertEquals(2, samples.size());
        var guideSample = samples.stream()
                .filter(s -> "local/guide".equals(s.identity().qualifiedName()))
                .findFirst().orElseThrow();
        var brokenSample = samples.stream()
                .filter(s -> "local/broken".equals(s.identity().qualifiedName()))
                .findFirst().orElseThrow();

        assertEquals(1L, guideSample.invokeDelta());
        assertEquals(1L, guideSample.resourceReadDelta());
        assertEquals(0L, guideSample.materializeFailDelta());
        assertEquals(1L, brokenSample.materializeFailDelta());
    }

    private static final class StubTool extends ToolCall {
        StubTool(String name) {
            setName(name);
            setDescription("stub");
        }

        @Override
        public ToolCallResult execute(String arguments) {
            return ToolCallResult.completed("ok");
        }
    }

    private static final class StubSkillProvider implements SkillProvider {
        private final List<SkillMetadata> skills;

        StubSkillProvider(List<SkillMetadata> skills) {
            this.skills = skills;
        }

        @Override
        public List<SkillMetadata> listSkills() {
            return skills;
        }

        @Override
        public String readContent(SkillMetadata skill) {
            if ("broken".equals(skill.getName())) {
                throw new SkillLoadException("corrupt package");
            }
            return "# Guide";
        }

        @Override
        public String readResource(SkillMetadata skill, String resourcePath) {
            return "resource body";
        }

        @Override
        public int priority() {
            return 10;
        }
    }
}
