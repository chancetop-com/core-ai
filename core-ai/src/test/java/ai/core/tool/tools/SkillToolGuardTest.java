package ai.core.tool.tools;

import ai.core.skill.SkillConfig;
import ai.core.skill.SkillToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillToolGuardTest {

    @TempDir
    Path temp;

    @Test
    void aDeniedSkillReturnsAFailedResultAndAnAllowedOneLoads() throws IOException {
        var skillDir = temp.resolve("demo");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"),
                "---\nname: demo\ndescription: demo skill\n---\n\nSay hello.\n");

        var allowed = new AtomicBoolean(false);
        var provider = new SkillToolProvider(SkillConfig.of(temp.toString()), temp.toString(),
                skill -> allowed.get());
        var tool = provider.provide().get(SkillTool.TOOL_NAME);

        var denied = tool.execute("{\"name\":\"demo\"}");
        assertTrue(denied.isFailed());
        assertTrue(denied.getResult().contains("unavailable"));

        allowed.set(true);
        var permitted = tool.execute("{\"name\":\"demo\"}");
        assertTrue(permitted.isCompleted());
        assertTrue(permitted.getResult().contains("Say hello"));
    }
}
