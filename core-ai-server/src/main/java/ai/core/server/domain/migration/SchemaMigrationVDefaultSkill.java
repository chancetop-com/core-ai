package ai.core.server.domain.migration;

import core.framework.mongo.Mongo;
import org.bson.Document;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Insert the default markmap-mindmap skill so it is available to all agents out of the box.
 *
 * @author stephen
 */
public class SchemaMigrationVDefaultSkill implements SchemaMigration {
    public static final String DEFAULT_SKILL_ID = "default-markmap-mindmap";

    private static final String SKILL_CONTENT = """
            ---
            name: markmap-mindmap
            description: Generate interactive Markmap mindmap HTML from markdown using npx markmap-cli. Use when you need to visualize markdown content as an interactive, zoomable mindmap in a standalone HTML file.
            allowed-tools: Bash, Read, Write, Glob, WebFetch
            ---

            # Markmap Mindmap Skill

            Generate interactive, zoomable mindmaps as standalone HTML files using [markmap-cli](https://markmap.js.org/docs/packages--markmap-cli).

            ## Trigger

            Invoke this skill when the user asks to:
            - "create a mindmap" / "generate a mindmap"
            - "visualize this as a mindmap"
            - "make a markmap"
            - "turn this into an interactive mindmap"

            Input can be:
            - A **file path** — read the file and map it
            - A **URL** (`http://` or `https://`) — fetch the page and map its content
            - **Pasted text / notes** — map the text directly
            - A **topic** — generate a map from your own knowledge

            ## Prerequisites

            Node.js must be installed (provides `npx`). No global install needed — `npx` fetches `markmap-cli` on demand.

            Verify with: `npx --yes markmap-cli --version`

            ## Workflow

            ### Step 1: Resolve the Input

            Classify the input and load the content:

            1. If it is a **URL** (starts with `http://` or `https://`), fetch it with `WebFetch` (ask for the page's main text content).
            2. Else if it is the path of an **existing file**, `Read` it.
            3. Else if it is **long or multi-line prose** (roughly > 12 words, or contains line breaks), treat it as **raw text**.
            4. Else treat it as a **topic**: generate the content from your own knowledge.

            Stop and ask the user when:
            - A **URL fetch fails** — report it and ask whether to map from your own knowledge instead.
            - A **file path** that looks like a file but **does not exist** — report and ask.
            - The input is **empty or whitespace only** — ask for content.

            ### Step 2: Build the Markmap Structure

            Turn the content into markmap-compatible markdown with this format:

            ```
            ---
            title: <Map Title>
            markmap:
              colorFreezeLevel: 2
              maxWidth: 300
            ---

            # <Central Topic>

            ## <Branch 1>
            - <point>
              - <sub-point>
            - <point>

            ## <Branch 2>
            - <point>
            ```

            Rules:
            - Exactly **one `# H1`** — the root / central topic.
            - `## H2` = main branches; `###` and `-` bullets = deeper levels.
            - **Depth:** aim for 3–4 levels.
            - **Phrases, not sentences:** every node is a short label (≤ ~8 words).
            - **Legibility over completeness:** target 4–7 main branches. Condense aggressively.
            - If the content is already structured (headings/bullets), follow that outline but condense.
            - Inline markdown (`**bold**`, `` `code` ``, links) is allowed and passes through.
            - Keep the frontmatter defaults (`colorFreezeLevel: 2`, `maxWidth: 300`).

            ### Step 3: Write the Markmap `.md` File

            Write the structured markmap content to a `.md` file.

            Output path:
            - File input `foo.md` → `foo.mindmap.md` (same directory).
            - URL input → slug of the page title (or URL's last path segment) → `<slug>.mindmap.md` in current directory.
            - Raw-text input → slug of the map's H1 title → `<slug>.mindmap.md` in current directory.
            - Topic input → `<topic-slug>.mindmap.md` in current directory (slug = lowercase, spaces → `-`).

            **Before writing, check whether the target already exists.** If it does, insert a counter — `<name>-2.mindmap.md`, then `<name>-3.mindmap.md`, … — and use the first name that is free. **Never overwrite an existing file silently.**

            ### Step 4: Render to HTML with markmap-cli

            Run markmap-cli to generate the interactive HTML:

            ```bash
            npx --yes markmap-cli "<input.md>" -o "<output.html>" --no-open
            ```

            Options used:
            - `--yes` — auto-confirm npx install prompt (required for non-interactive use)
            - `-o <output>` — specify output HTML path
            - `--no-open` — don't try to open the browser (this is headless CLI)

            The `.html` path should match the `.md` path but with `.html` extension (e.g., `note.mindmap.html`).

            **If `npx` / Node.js is not available**, the `.md` file is still the guaranteed deliverable. Tell the user the `.md` was written, explain that rendering was skipped because Node.js was not found, and provide the manual command:

            ```bash
            npx --yes markmap-cli "<input.md>" -o "<output.html>"
            ```

            ### Step 5: Report

            Tell the user:
            - The `.md` file path (can be opened with VS Code Markmap extension or at https://markmap.js.org)
            - The `.html` file path (standalone interactive mindmap, open in any browser)
            - How to view: drag the HTML file into a browser, or open the `.md` at https://markmap.js.org

            ## markmap-cli Quick Reference

            ```
            Usage: npx markmap-cli [options] <input>

            Options:
              -V, --version          output the version number
              --no-open              do not open the output file after generation
              --no-toolbar           do not show toolbar
              -o, --output <output>  specify filename of the output HTML
              -w, --watch            watch the input file and update output on the fly
              -h, --help             display help for command
            ```

            By default, without `-o`, markmap-cli writes the HTML next to the input file with the same basename (e.g., `note.md` → `note.html`).

            ## Example

            Input: user provides a structured markdown file `rag.md`:

            ```
            # Retrieval-Augmented Generation
            ## Indexing
            Chunk documents, embed them, store the vectors.
            ## Retrieval
            Embed the query, find the nearest chunks.
            ## Generation
            Inject the retrieved context into the prompt.
            ```

            Output `rag.mindmap.md`:

            ```
            ---
            title: Retrieval-Augmented Generation
            markmap:
              colorFreezeLevel: 2
              maxWidth: 300
            ---

            # Retrieval-Augmented Generation

            ## Indexing
            - Chunk documents
            - Embed chunks
            - Store vectors

            ## Retrieval
            - Embed the query
            - Find nearest chunks

            ## Generation
            - Inject context into prompt
            ```

            Then render: `npx --yes markmap-cli "rag.mindmap.md" -o "rag.mindmap.html" --no-open`

            ## Notes

            - The `.md` file is always the guaranteed deliverable — HTML rendering is best-effort and depends on Node.js availability.
            - `--no-toolbar` can be added to remove the toolbar from the HTML output for a cleaner look.
            - The generated HTML is fully self-contained — no server needed, just open in any browser.
            """;

    @Override
    public String version() {
        return "20260709002";
    }

    @Override
    public String description() {
        return "create default markmap-mindmap skill";
    }

    @Override
    public void migrate(Mongo mongo) {
        var now = Date.from(Instant.now());
        var skill = new Document()
                .append("_id", DEFAULT_SKILL_ID)
                .append("namespace", "system")
                .append("name", "markmap-mindmap")
                .append("qualified_name", "system/markmap-mindmap")
                .append("description", "Generate interactive Markmap mindmap HTML from markdown using npx markmap-cli. Use when you need to visualize markdown content as an interactive, zoomable mindmap in a standalone HTML file.")
                .append("source_type", "upload")
                .append("content", SKILL_CONTENT)
                .append("allowed_tools", List.of("Bash", "Read", "Write", "Glob", "WebFetch"))
                .append("metadata", Map.of(
                        "name", "markmap-mindmap",
                        "description", "Generate interactive Markmap mindmap HTML from markdown using npx markmap-cli. Use when you need to visualize markdown content as an interactive, zoomable mindmap in a standalone HTML file."
                ))
                .append("user_id", "system")
                .append("created_at", now)
                .append("updated_at", now);

        var filter = new Document("_id", DEFAULT_SKILL_ID);
        var update = new Document("$setOnInsert", skill);
        mongo.runCommand(new Document("update", "skills")
                .append("updates", List.of(new Document("q", filter).append("u", update).append("upsert", true))));
    }
}
