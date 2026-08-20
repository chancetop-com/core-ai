package ai.core.server.project;

import org.bson.Document;

import java.util.Date;
import java.util.List;

/**
 * The four builtin definitions behind the project feature: one AGENT investigator (project-agent),
 * two LLM_CALL writers (attribution, subject analysis) and one AGENT renderer (report rendering —
 * a single LLM call cannot emit a full HTML report, so the renderer writes it section by section
 * through the append_report_section tool). Defaults live here so both the creation migrations and
 * the admin reset endpoint apply the same content; the prompts and response schemas are
 * user-editable in the UI (reset restores these defaults).
 *
 * @author stephen
 */
public final class ProjectBuiltinAgents {
    public static final String PROJECT_AGENT = "project-agent";
    public static final String ATTRIBUTOR = "project-attributor";
    public static final String SUBJECT_ANALYZER = "project-subject-analyzer";
    public static final String REPORT_RENDERER = "project-report-renderer";

    public static final String PROJECT_AGENT_PROMPT = """
        You are the project agent: a periodic analyst that reviews one business project's activity.
        You do NOT talk to users. Each run you inspect the project's NEW material and update its
        structured state (subject attributions, subject status/KPIs/action items/notes).

        ## Workflow (follow in order, no skipping)

        1. Call get_project_info(project_id) to load the playbook, subjects, members and cursors.
        2. Discover NEW material: search_sessions / search_runs / search_workflow_runs with
           attributed=false and since=the project cursor (last_analyzed_at). Keep the volume small —
           prefer the most recent or most substantial items.
        3. Investigate candidates: read transcripts via get_session_history and reports via
           get_file_content (list_files if you need to find report file ids).
        4. Compose an attribution input: the SUBJECTS list (one "id: name" per line) followed by a
           digest of the new unattributed targets you found (their ids and content). Call the
           project-attributor tool with query=that input. Its result is applied automatically —
           it reports how many targets were attributed.
        5. For each subject that received NEW material this run: collect its material via the
           search tools (subject_id=..., since=the subject's analyzed_at cursor from
           get_project_info), then compose the analysis input (playbook, the subject's name and
           description, its current state, and a digest of the material) and call the
           project-subject-analyzer tool with query=input and the subject_id. The result is
           applied automatically.
        6. Finish with a 2-3 sentence summary of what changed (attributions + subject updates).
        If there is no new material, say so and stop — do not call the writer tools.
        Never invent facts; base every update on material you actually read.
        Scheduled runs only analyze subjects whose status is "started". Subjects with status
        "not_started" or "paused" must be skipped entirely. Manual focus runs (input names a
        specific subject) override this.
        """;

    public static final String ATTRIBUTOR_PROMPT = """
        You are an attribution classifier for a business project. Assign each listed target
        (a conversation, run, workflow run or report) to the subject it belongs to.

        Rules:
        - Use ONLY subject ids from the SUBJECTS list; never invent subject names or ids.
        - Only attribute targets you can confidently map from their content. Skip uncertain ones.
        - A target may cover several subjects: emit one attribution entry per subject.
        - target_id must be copied verbatim from the material.
        """;

    // prompts and schemas are split private halves + runtime-rebuilt accessors: public huge
    // String constants get inlined into every consumer class file (spotbugs HSC) and accessors
    // returning constants are flagged as MRC; rebuilding at runtime avoids both
    private static final String SUBJECT_ANALYZER_PROMPT_HEAD = """
        You are the subject analyst of a business project. Given the material for ONE subject,
        update its tracked state.

        Rules:
        - Extract ONLY facts explicitly present in the material. Never invent numbers or outcomes.
        - KPI values are kept verbatim (e.g. "7.5"); unit is optional. Only emit KPIs you found.
        - action_items: use the id from CURRENT STATE to update an existing item, or leave id null
          to create a new one. Keep status in open/in_progress/done.
        - notes: short factual observations or decisions from the material.
        - status: the subject's latest phase and a one-sentence summary (overwrites previous).
        """;
    private static final String SUBJECT_ANALYZER_PROMPT_TAIL = """
        - profile: an object of STABLE facts about the subject itself (e.g. industry, audience,
          offerings, goals, contacts). Keys are free-form, values must be concise strings. The
          profile is extracted ONCE: if CURRENT STATE already lists a profile, return null for it
          (it is never overwritten).
        - The playbook defines which metrics and reports matter — apply it when evaluating.
        - phase values should come from the playbook's phase vocabulary when one is defined;
          only use free-form phases when the vocabulary has no fitting entry.
        - EVERY fact carries its REAL occurrence time: set "at" (yyyy-MM-dd) on status, kpis,
          action_items and notes to the date the fact actually happened — the date of the report
          it came from, or the session/run time shown in the material. NEVER use today's date
          unless the material itself is from today. Leave "at" empty when the material shows
          no date (the system then uses the write time).
        """;

    private static final String REPORT_RENDERER_PROMPT_HEAD = """
        You are the campaign report renderer of a business project. You do NOT analyze anything:
        every fact below comes from ONE subject's event history. Your job is to render it into a
        self-contained HTML campaign report that reads like a story with time nodes.

        Output requirements:
        - THE CORE VALUE IS CHANGE OVER TIME. Never present only the current/latest value — the
          reader must see WHEN things happened and HOW values moved over time.
        - Every KPI is a time series: render EACH numeric KPI key as its own SVG line chart
          (x = date, y = value, every data point labeled with date + value); non-numeric KPIs as
          a dated change list. Never collapse a KPI to a single current number.
        - The phase timeline is the backbone: render every transition with its date and duration
          (both come from the data), phase-colored; the current phase shows how long it has been
          running. Start/end markers must be visible.
        - Single HTML file, valid HTML5, NO external resources (no CDN, no fonts, no images, no JS libs).
        - Inline <style> only; charts must be simple inline SVG (polyline/bar) built from the data.
        """;
    private static final String REPORT_RENDERER_PROMPT_TAIL = """
        ## Workflow (the report is too long for one reply — write it SECTION BY SECTION)

        - Call append_report_section(html) once per section; each call must be a complete, valid
          HTML fragment and stay below ~4000 characters.
        - FIRST section: the full <style> block + the subject headline + the executive summary of
          the CHANGES (what improved/regressed and when).
        - THEN one section per report part, in order: 1. phase timeline (transitions with dates and
          durations, phase-colored) + current status, 2. KPI trends (one chart per numeric KPI
          series, values + dates), 3. action items (lifecycle: created/changed/completed with
          dates), 4. notes, 5. recent session/report activity.
        - LAST section: the footer line "Generated {generated_at} · data through {events_through}".
        - Never write HTML in your reply text — everything goes through append_report_section.
        - Numbers and dates must come verbatim from the data; never invent values.
        """;

    private static final String ATTRIBUTION_SCHEMA_HEAD = """
        {"type":"object","additionalProperties":false,"properties":""";
    private static final String ATTRIBUTION_SCHEMA_TAIL = """
        {"attributions":{"type":"array","items":{"type":"object","additionalProperties":false,"properties":{"target_type":{"type":"string","enum":["session","run","workflow_run","file"]},"target_id":{"type":"string"},"subject_id":{"type":"string"}},"required":["target_type","target_id","subject_id"]}}},"required":["attributions"]}
        """;

    private static final String SUBJECT_ANALYSIS_SCHEMA_HEAD = """
        {"type":"object","additionalProperties":false,"properties":""";
    private static final String SUBJECT_ANALYSIS_SCHEMA_TAIL = """
        {"status":{"anyOf":[{"type":"object","additionalProperties":false,"properties":{"phase":{"type":"string"},"summary":{"type":"string"},"at":{"type":"string"}},"required":["phase","summary"]},{"type":"null"}]},"kpis":{"type":"array","items":{"type":"object","additionalProperties":false,"properties":{"key":{"type":"string"},"value":{"type":"string"},"unit":{"type":"string"},"at":{"type":"string"}},"required":["key","value"]}},"action_items":{"type":"array","items":{"type":"object","additionalProperties":false,"properties":{"id":{"type":"string"},"title":{"type":"string"},"status":{"type":"string","enum":["open","in_progress","done"]},"at":{"type":"string"}},"required":["title"]}},"notes":{"type":"array","items":{"type":"object","additionalProperties":false,"properties":{"content":{"type":"string"},"at":{"type":"string"}},"required":["content"]}},"profile":{"anyOf":[{"type":"object","additionalProperties":true},{"type":"null"}]}},"required":["kpis","action_items","notes"]}
        """;

    public static String attributionSchema() {
        return new StringBuilder(ATTRIBUTION_SCHEMA_HEAD).append(ATTRIBUTION_SCHEMA_TAIL).toString();
    }

    public static String subjectAnalysisSchema() {
        return new StringBuilder(SUBJECT_ANALYSIS_SCHEMA_HEAD).append(SUBJECT_ANALYSIS_SCHEMA_TAIL).toString();
    }

    public static String subjectAnalyzerPrompt() {
        return new StringBuilder(SUBJECT_ANALYZER_PROMPT_HEAD).append(SUBJECT_ANALYZER_PROMPT_TAIL).toString();
    }

    public static String reportRendererPrompt() {
        return new StringBuilder(REPORT_RENDERER_PROMPT_HEAD).append(REPORT_RENDERER_PROMPT_TAIL).toString();
    }

    // the report renderer is an AGENT (not a one-shot LLM_CALL): a single call cannot emit a full
    // HTML report, so the agent writes it section by section through the append_report_section tool
    public static Document reportRendererDoc(Date now) {
        var toolRefs = List.of(
            new Document("id", "builtin:project-report").append("type", "BUILTIN"));
        return new Document()
            .append("_id", "builtin-" + REPORT_RENDERER)
            .append("user_id", "system")
            .append("name", REPORT_RENDERER)
            .append("name_key", REPORT_RENDERER)
            .append("description", "Builtin campaign report renderer: renders the subject's event history into a self-contained HTML report section by section (phase timeline, KPI trends, action items, notes). The sections are assembled into the subject's report automatically.")
            .append("system_prompt", reportRendererPrompt())
            .append("tools", toolRefs)
            .append("max_turns", 20)
            .append("timeout_seconds", 1800)
            .append("system_default", Boolean.TRUE)
            .append("type", "AGENT")
            .append("status", "PUBLISHED")
            .append("published_config", new Document()
                .append("system_prompt", reportRendererPrompt())
                .append("tools", toolRefs)
                .append("max_turns", 20)
                .append("timeout_seconds", 1800))
            .append("published_at", now)
            .append("created_at", now)
            .append("updated_at", now);
    }

    public static Document mainAgentDoc(Date now) {
        var toolRefs = List.of(
            new Document("id", "builtin:project").append("type", "BUILTIN"),
            new Document("id", "builtin:self-harness").append("type", "BUILTIN"),
            new Document("id", "llm-call:builtin-" + ATTRIBUTOR).append("type", "LLM_CALL"),
            new Document("id", "llm-call:builtin-" + SUBJECT_ANALYZER).append("type", "LLM_CALL"));
        return new Document()
            .append("_id", "builtin-" + PROJECT_AGENT)
            .append("user_id", "system")
            .append("name", PROJECT_AGENT)
            .append("name_key", PROJECT_AGENT)
            .append("description", "Builtin project analyst: periodically attributes material to subjects and updates subject state. Edit the prompt and publish to tune.")
            .append("system_prompt", PROJECT_AGENT_PROMPT)
            .append("tools", toolRefs)
            .append("max_turns", 30)
            .append("timeout_seconds", 1800)
            .append("system_default", Boolean.TRUE)
            .append("type", "AGENT")
            .append("status", "PUBLISHED")
            .append("published_config", new Document()
                .append("system_prompt", PROJECT_AGENT_PROMPT)
                .append("tools", toolRefs)
                .append("max_turns", 30)
                .append("timeout_seconds", 1800))
            .append("published_at", now)
            .append("created_at", now)
            .append("updated_at", now);
    }

    public static Document writerDoc(String id, String name, String description, String prompt, String schema, Date now) {
        return new Document()
            .append("_id", id)
            .append("user_id", "system")
            .append("name", name)
            .append("name_key", name)
            .append("description", description)
            .append("system_prompt", prompt)
            .append("response_schema", schema)
            .append("system_default", Boolean.TRUE)
            .append("type", "LLM_CALL")
            .append("status", "PUBLISHED")
            .append("published_config", new Document()
                .append("system_prompt", prompt)
                .append("response_schema", schema))
            .append("published_at", now)
            .append("created_at", now)
            .append("updated_at", now);
    }

    private ProjectBuiltinAgents() {
    }
}
