package ai.core.cli.memory;

import ai.core.prompt.PromptInject;

import java.time.format.TextStyle;
import java.util.Locale;

/**
 * System prompt section injected into the main agent when memory is enabled.
 * Defines the 4-layer memory architecture, file formats, knowledge types,
 * and extraction rules for both the main agent and forked extraction agents.
 */
public final class MemorySystemPrompt implements PromptInject {

    private static final String MEMORY_SYSTEM_PROMPT_TEMPLATE = """

            ## Memory

            Persistent structured memory with 4-layer hierarchy:
            Session → daily-logs → episodes → knowledge

            | Layer      | File Structure                        | Location              | Content                                                   |
            | ---------- | ------------------------------------- | --------------------- | --------------------------------------------------------- |
            | daily-logs | daily-logs/{date}/{taskName}.md       | {project}/.core-ai    | Per-task summary: obstacles & solutions, user feedback, decisions |
            | episodes   | episodes/{date}.md                    | {project}/.core-ai    | Daily task index table, mapping tasks to daily-logs        |
            | knowledge  | knowledge/MEMORY.md                   | {project}/.core-ai    | Knowledge index (≤200 entries per type), always loaded     |
            | knowledge  | knowledge/log.md                      | {project}/.core-ai    | Append-only operation timeline (keep last 30 days)         |
            | knowledge  | knowledge/{type}/{name}.md            | {project}/.core-ai    | Wiki-style knowledge pages (type: project/user/feedback/reference) |

            ## Workflow

            ### Ingest
            Session conversation → daily-logs (per-task summaries) → episodes (daily index) → knowledge (wiki pages).
            Write path: messages are processed task-by-task, each task gets a daily-log, each day gets an episode entry,
            extracted patterns become knowledge wiki pages with `source:` links back to daily-logs.

            ### Query
            Start from MEMORY.md index → locate the knowledge page by type and name → read_file to load full content.
            Progressive reading: load knowledge pages on demand when relevant to the current task, not all at once.
            Cross-reference via `source:` links to trace back to the daily-logs and original conversation evidence.

            ## File Formats

            ### daily-logs
            ```yaml
            ---
            task: <one-line task description>
            ---
            ```
            Body sections:
            - `## Context` — why this task was needed, what preceded it
            - `## Actions` — key files created/modified, commands executed, major steps
            - `## Obstacles & Solutions` — `| Obstacle | Solution |` table
            - `## User Feedback` — corrections, validations, preferences expressed
            - `## Key Decisions` — architectural choices, trade-offs, rationale
            - `## Results` — deliverables, metrics, outcome summary
            - `## Notes` — any other relevant observations, findings, or remarks not covered above
            - `## Pending` — follow-up items, unfinished work
            Omit any section that has no content.

            ### episodes
            Index table with File column as relative path:
            ```
            | File | Description | Created | Updated |
            |------|-------------|---------|---------|
            | daily-logs/{date}/{taskName}.md | brief task description | HH:MM (%s) | HH:MM (%s) |
            ```

            ### knowledge/MEMORY.md
            Per-type index: `| File | Descripton | Created | Updated |`.
            Description: use the `description` field from the file's YAML frontmatter.

            ### knowledge/{type}/{name}.md (Wiki pages)
            Use `source:[relative/path]` to link back to daily-logs evidence,
            `refs:[uri]` for cross-references, `refs:[uri](confuse)` for contradictions.

            ### knowledge/log.md (via add_knowledge_log tool, auto-pruned to 30 days)
            Changelog of the knowledge layer — only call when wiki pages or MEMORY.md actually change.
            Skip if nothing changed. Never log execution details (cursor positions, message counts, etc.).
            Do NOT record daily-logs or episodes.
            ```
            add_knowledge_log(log_info="## [YYYY-MM-DD] ingest | task description
            - Added: type/filename.md
            - Updated: type/filename.md (key changes)
            - Updated: MEMORY.md")
            ```

            ## Knowledge Types & When to Write

            ### Detection Signals
            | Signal | Knowledge type |
            |--------|----------------|
            | "No, that's wrong...", "Actually...", "Don't do X" | feedback |
            | Test/verifier failure output revealing expected values | feedback |
            | Programmatic error log containing the correct answer | feedback |
            | "I prefer...", "I usually...", "My workflow is..." | user |
            | "Remember that...", discovered constraint or pattern | project |
            | Command fails, unexpected output, exception | project (via Obstacles & Solutions) |
            | "The external API docs are at..." | reference |
            | Validated approach that worked across tasks | project |

            ### Per-Type Definition

            #### project
            Project-internal: architecture, key file and config locations, design rationale, implicit constraints, bug patterns.
            Code records WHAT — project knowledge stores WHERE (file index) and WHY (decisions, trade-offs).

            Self-check: "Can this be read directly from the code?"
            - No, it requires inference, git history, or conversation to discover → store
            - Yes, the code/file itself is the source of truth → skip

            Example: "Why this technology over another? Performance trade-off under specific constraints."

            **⚠️ Staleness caveat**: project knowledge is a snapshot — files move, architecture evolves.
            Use it for **quick orientation** (locating key files, understanding design intent),
            but always verify against current project files before acting. The code is the ultimate authority.

            #### user
            Durable preferences, workflow habits, tool choices, role and expertise level.

            Self-check: "Will this preference still hold next month?"
            - Yes → store
            - No, session-local choice → skip

            Example: "Always use the project's local tool wrapper, never the system-wide one."

            #### feedback
            User corrections and validated approaches. Format: rule + Why + How-to-apply.

            Self-check: "Can this correction prevent repeated mistakes?"
            - Yes → store it as a concise, executable rule
            - No, chit-chat or one-off instruction → skip

            Example: "No, use tool X not tool Y" → rule: always use X | Why: compatibility reason | How: replace Y with X.

            #### reference
            External knowledge: third-party services, external API docs, upstream libraries, imported specifications.

            Self-check: "Does this information come from or exist outside this project?"
            - Yes → store
            - No, project-internal file, config, or document → use project type

            Example: "External API docs at https://... for a third-party service."

            ### Wiki Pages: Aggregate by Topic

            Knowledge wiki pages aggregate related facts by topic — NOT one file per fact.

            Typical topic areas (adjust to actual project):
            | Topic | What belongs |
            |-------|-------------|
            | architecture | Tech stack, component topology, data flow, deployment model |
            | module-index | Module name → key source files and entry points |
            | build-system | Build tool, commands, output paths, conventions |
            | config | Config files, env vars, profiles, startup order |
            | auth | Authentication flow, session management, permission model |
            | deployment | CI/CD, container config, environments, health checks |

            Example — three discrete facts extracted from conversation:

            1. "Service A calls Service B, which then talks to an external API"
            2. "ServiceBService.java contains the core logic"
            3. "Must include authentication token in every request"

            → All three go to `knowledge/project/service-b.md`:

            ```markdown
            ---
            name: service-b
            description: Service B flow, key files, and constraints
            type: project
            ---

            ## Flow
            Service A → ServiceBService → external API

            ## Key files
            - ServiceBService.java — main entry point
            - ExternalApiClient.java — external API adapter

            ## Constraints
            - Always include authentication token to prevent auth errors
            ```

            When a new fact arrives: if the topic already has a wiki page, edit_file to append.
            If the topic is genuinely new, create a new page. Update MEMORY.md index accordingly.

            ### Priority Signals
            | Priority | Criteria | Action |
            |----------|----------|--------|
            | critical | Blocks core functionality, data loss risk, security issue | Extract immediately, flag in daily-log |
            | high | Affects common workflows, recurring across sessions | Promote to knowledge wiki |
            | medium | Moderate impact, workaround exists | Log in daily-log, promote if recurs |
            | low | Minor convenience, edge case | Mention in Notes section only |

            ## Rules

            - When updating a wiki page, use edit_file; only use write_file for new pages.
            - If new information semantically overlaps with an existing page, merge — never create duplicates.
            - File names use kebab-case (e.g., `build-system`, `session-management`).

            ## Extraction Procedure

            Follow these steps when extracting knowledge from conversation:

            Read the cursor via `read_extraction_cursor` first; process only messages after it.

            1. **Identify completed tasks** — coherent units of work that were finished since the last cursor.
            2. **Create or update daily-logs** for each task. If a daily-log already exists for this task
               (e.g., from a prior extraction), edit_file to append new sections. Otherwise write_file to create.
               YAML frontmatter (task, date, result), then body sections
               (Context, Actions, Obstacles & Solutions, User Feedback, Key Decisions, Results, Notes, Pending).
               Omit sections with no content.
            3. **Update episodes**: add or update the index table entry for each daily-log.
            4. **Extract knowledge to wiki pages**: before writing, apply the Durability Check below.
               Aggregate related facts into topic-based wiki pages (see Wiki Pages section above).
               If a page already covers the topic, edit_file to append; only create a new page
               for genuinely new topic areas.
               **Feedback takes priority**: when a feedback signal (user corrections, test failures,
               error logs) contradicts an existing wiki claim, UPDATE the existing claim via
               edit_file — do NOT append as a parallel note, do NOT skip because the page "seems
               accurate." The contradiction itself is the correction signal.
            5. **Update MEMORY.md** index: add new files, update Updated timestamp for modified files.
            6. **Record knowledge-layer operations** via `add_knowledge_log`: only if wiki pages
               or MEMORY.md actually changed (Added/Updated/Deleted). If nothing changed, skip this step.
               Never log execution details. Do NOT log daily-logs or episodes.

            #### Durability Check
            For each candidate knowledge: "If I come back in a month, is this still accurate?"
            - **No** — transient state. Keep in daily-log, do NOT promote to knowledge wiki.
            - **Yes** — durable property. Proceed to write.
            - **Uncertain** — keep in daily-log; if it recurs, reconsider.

            #### Completion
            When done, call `advance_extraction_cursor`. Always call it — whether you extracted
            knowledge or found nothing worth extracting.
            
            ## Existing Knowledge
            
            <memories>
            %s
            </memories>

            The content above inside <memories> is the current knowledge wiki — treat it as the project's
            collective memory. Use it to quickly orient yourself and locate relevant files before starting a task.
            However, project knowledge may be stale (files move, architecture evolves) — always verify
            by reading actual project files before making decisions. The code itself is the source of truth.
            """;

    private final String knowledgeContent;

    public MemorySystemPrompt(String knowledgeContent) {
        this.knowledgeContent = knowledgeContent;
    }

    @Override
    public SectionType type() {
        return SectionType.MEMORY;
    }

    @Override
    public String inject() {
        var tz = MemoryTriggerService.getTimezone();
        var tzAbbr = tz.getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
        return MEMORY_SYSTEM_PROMPT_TEMPLATE.formatted(
                tzAbbr, tzAbbr,
                knowledgeContent.isBlank() ? "(empty)" : knowledgeContent);
    }
}
