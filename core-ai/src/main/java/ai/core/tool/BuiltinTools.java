package ai.core.tool;

import ai.core.tool.tools.CaptionImageTool;
import ai.core.tool.tools.EditFileTool;
import ai.core.tool.tools.GlobFileTool;
import ai.core.tool.tools.GrepFileTool;
import ai.core.tool.tools.PythonScriptTool;
import ai.core.tool.tools.ReadFileTool;
import ai.core.tool.tools.ShellCommandTool;
import ai.core.tool.tools.SummarizePdfTool;
import ai.core.tool.tools.WebFetchTool;
import ai.core.tool.tools.WebSearchTool;
import ai.core.tool.tools.WriteFileTool;
import ai.core.tool.tools.WriteTodosTool;

import java.util.Arrays;
import java.util.List;

/**
 * @author stephen
 */
public final class BuiltinTools {

    public static final List<ToolCall> ALL = List.of(
            // Plan
            WriteTodosTool.self(),
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
            PythonScriptTool.builder().build()
    );

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

    @SafeVarargs
    @SuppressWarnings("varargs")
    public static List<ToolCall> combine(List<ToolCall>... toolLists) {
        return Arrays.stream(toolLists)
            .flatMap(List::stream)
            .toList();
    }
}
