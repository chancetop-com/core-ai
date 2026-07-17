package ai.core.tool;

import ai.core.tool.registry.ToolProvider;
import ai.core.tool.tools.CaptionImageTool;
import ai.core.tool.tools.EditFileTool;
import ai.core.tool.tools.GenerateImageTool;
import ai.core.tool.tools.GenerateVideoTool;
import ai.core.tool.tools.GetVideoStatusTool;
import ai.core.tool.tools.GlobFileTool;
import ai.core.tool.tools.GrepFileTool;
import ai.core.tool.tools.PowershellCommandTool;
import ai.core.tool.tools.PythonScriptTool;
import ai.core.tool.tools.ReadFileTool;
import ai.core.tool.tools.RequireGithubInstallationTokenTool;
import ai.core.tool.tools.ShellCommandTool;
import ai.core.tool.tools.SummarizePdfTool;
import ai.core.tool.tools.TaskTool;
import ai.core.tool.tools.WebFetchTool;
import ai.core.tool.tools.WebSearchTool;
import ai.core.tool.tools.WriteFileTool;
import ai.core.tool.tools.WriteTodoTaskTool;
import ai.core.tool.tools.WriteTodosTool;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public final class BuiltinTools {

    public static final List<ToolCall> ALL = List.of(
            // Plan
            WriteTodosTool.self(),
            TaskTool.builder().build(),
            // File operations
            ReadFileTool.builder().build(),
            WriteFileTool.builder().build(),
            EditFileTool.builder().build(),
            GlobFileTool.builder().build(),
            GrepFileTool.builder().build(),
            // Multimodal
            CaptionImageTool.builder().build(),
            SummarizePdfTool.builder().build(),
            // Web
            WebFetchTool.builder().build(),
            WebSearchTool.builder().build(),
            // Code execution
            ShellCommandTool.builder().build(),
            PythonScriptTool.builder().build(),
            // GitHub
            RequireGithubInstallationTokenTool.builder().build(),
            // Media generation
            GenerateImageTool.builder().build(),
            GenerateVideoTool.builder().build(),
            GetVideoStatusTool.builder().build()
    );

    public static final List<ToolCall> PLANNING = List.of(
            WriteTodosTool.self(),
            TaskTool.builder().build()
    );
    public static final List<ToolCall> PLANNING_V2 = combine(WriteTodoTaskTool.self(), List.of(TaskTool.builder().build()));

    public static final List<ToolCall> FILE_OPERATIONS = List.of(
            ReadFileTool.builder().build(),
            WriteFileTool.builder().build(),
            EditFileTool.builder().build(),
            GlobFileTool.builder().build(),
            GrepFileTool.builder().build()
    );

    public static final List<ToolCall> FILE_READ_ONLY = List.of(
            ReadFileTool.builder().build(),
            GlobFileTool.builder().build(),
            GrepFileTool.builder().build()
    );

    public static final List<ToolCall> MULTIMODAL = List.of(
            CaptionImageTool.builder().build(),
            SummarizePdfTool.builder().build()
    );

    public static final List<ToolCall> WEB = List.of(
            WebFetchTool.builder().build(),
            WebSearchTool.builder().build()
    );

    public static final List<ToolCall> CODE_EXECUTION = List.of(
            ShellCommandTool.builder().build(),
            PythonScriptTool.builder().build()
    );
    public static final List<ToolCall> BASH_EXECUTION = List.of(
            ShellCommandTool.builder().build()
    );
    public static final List<ToolCall> POWERSHELL_EXECUTION = List.of(
            PowershellCommandTool.builder().build()
    );
    public static final List<ToolCall> FILE_RW = List.of(
            WriteFileTool.builder().build(),
            EditFileTool.builder().build()
    );

    public static final List<ToolCall> GITHUB = List.of(
            RequireGithubInstallationTokenTool.builder().build()
    );

    public static final List<ToolCall> MEDIA_GENERATION = List.of(
            GenerateImageTool.builder().build(),
            GenerateVideoTool.builder().build(),
            GetVideoStatusTool.builder().build()
    );

    public static final Map<String, List<ToolCall>> GROUPED_SETS = Map.ofEntries(
            Map.entry(ToolProvider.BUILTIN_ALL, ALL),
            Map.entry(ToolProvider.BUILTIN_PLANNING, PLANNING),
            Map.entry(ToolProvider.BUILTIN_FILES, FILE_OPERATIONS),
            Map.entry(ToolProvider.BUILTIN_FILE_OPERATIONS, FILE_OPERATIONS),
            Map.entry(ToolProvider.BUILTIN_FILE_READ_ONLY, FILE_READ_ONLY),
            Map.entry(ToolProvider.BUILTIN_MULTIMODAL, MULTIMODAL),
            Map.entry(ToolProvider.BUILTIN_WEB, WEB),
            Map.entry(ToolProvider.BUILTIN_BASH, BASH_EXECUTION),
            Map.entry(ToolProvider.BUILTIN_CODE_EXECUTION, CODE_EXECUTION),
            Map.entry(ToolProvider.BUILTIN_GITHUB, GITHUB),
            Map.entry(ToolProvider.BUILTIN_MEDIA_GENERATION, MEDIA_GENERATION)
    );

    @SafeVarargs
    @SuppressWarnings("varargs")
    public static List<ToolCall> combine(List<ToolCall>... toolLists) {
        return Arrays.stream(toolLists)
                .flatMap(List::stream)
                .toList();
    }
}
