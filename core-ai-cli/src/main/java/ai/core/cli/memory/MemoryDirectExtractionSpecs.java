package ai.core.cli.memory;

final class MemoryDirectExtractionSpecs {

    static final String DIRECT_EXTRACTION_SPEC = """
            ## Memory (Direct Mode)

            Knowledge is stored directly from conversation into wiki pages — no intermediate layers.

            ⚠️ **Legacy daily-logs**: You may see existing `daily-logs/` and `episodes/` directories
            with `source:` links in wiki pages. These are artifacts from a previous mode. In direct
            mode, ignore them — do NOT create new daily-logs, do NOT update episodes, do NOT add
            `source:` links. Extract directly to wiki pages only.

            | Layer     | File Structure                        | Location              | Content                                                   |
            |-----------|---------------------------------------|------------------------|------------------------------------------------------------|
            | knowledge | knowledge/MEMORY.md                   | {project}/.core-ai    | Knowledge index (≤200 entries per type), always loaded     |
            | knowledge | knowledge/log.md                      | {project}/.core-ai    | Append-only operation timeline (keep last 30 days)         |
            | knowledge | knowledge/{type}/{name}.md            | {project}/.core-ai    | Wiki-style knowledge pages (type: project/user/feedback/reference) |

            ## Workflow

            ### Ingest
            Conversation → knowledge wiki pages directly. Extract durable facts and write them
            to wiki pages. No intermediate daily-logs or episodes.

            ### Query
            Start from MEMORY.md index → locate the knowledge page by type and name → read_file to load full content.
            Progressive reading: load knowledge pages on demand when relevant to the current task, not all at once.

            ## File Formats

            ### knowledge/MEMORY.md
            Per-type index with 4 columns:
            `| File | Description | When to Use | Created | Updated |`.
            Description: summarize the page topic in one sentence.
            When to Use: what scenario or question triggers loading this page.

            ### knowledge/{type}/{name}.md (Wiki pages)
            Body sections aggregate related facts by topic (see Wiki Pages section below).

            ### knowledge/log.md (via add_knowledge_log tool, auto-pruned to 30 days)
            Changelog of the knowledge layer — only call when wiki pages or MEMORY.md actually change.
            Skip if nothing changed. Never log execution details (cursor positions, message counts, etc.).
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
            | Command fails, unexpected output, exception | project |
            | "The external API docs are at..." | reference |
            | Validated approach that worked across tasks | project |

            ### Process vs. Outcome
            **Extract process, not just results.** The most valuable knowledge is how a problem
            was approached and solved — the reasoning, SOPs, and strategies. Bare numbers
            without context are transient; results that establish baselines or prove an
            approach are durable.

            | Durable (extract) | Contextual result (extract if useful) | Transient (skip) |
            |-------------------|---------------------------------------|------------------|
            | Problem-solving strategies and SOPs | Benchmark baselines ("GPT-4o scores 85% on dataset X") | Specific output values without context |
            | Debugging heuristics and troubleshooting patterns | Comparative results ("switching to Y improved QPS 2x") | One-off benchmark scores without baseline |
            | Design rationale and architectural trade-offs | Version/time-anchored metrics (for future regression checks) | Result of a single run with no comparison |
            | Reusable verification approaches | Validated cause-effect ("added index → latency dropped 50%") | Temporary workarounds for specific inputs |
            | Cross-task patterns ("every time I see X, I do Y") | Aggregated stats from systematic evaluation | Session-local configuration tweaks |

            When in doubt: does this result help answer a question next month under
            different conditions? If yes, extract with context; if no, skip.

            ### Per-Type Definition

            #### project
            Project-internal: architecture, key file and config locations, design rationale, implicit constraints, bug patterns.
            Code records WHAT — project knowledge stores WHERE (file index) and WHY (decisions, trade-offs).

            Self-check: "Can this be read directly from the code?"
            - No, it requires inference, git history, or conversation to discover → store
            - Yes, the code/file itself is the source of truth → skip

            **⚠️ Staleness caveat**: project knowledge is a snapshot — files move, architecture evolves.
            Use it for **quick orientation** (locating key files, understanding design intent),
            but always verify against current project files before acting. The code is the ultimate authority.

            #### user
            Durable preferences, workflow habits, tool choices, role and expertise level.

            Self-check: "Will this preference still hold next month?"
            - Yes → store
            - No, session-local choice → skip

            #### feedback
            User corrections and validated approaches. Format: rule + Why + How-to-apply.

            Self-check: "Can this correction prevent repeated mistakes?"
            - Yes → store it as a concise, executable rule
            - No, chit-chat or one-off instruction → skip

            #### reference
            External knowledge: third-party services, external API docs, upstream libraries, imported specifications.

            Self-check: "Does this information come from or exist outside this project?"
            - Yes → store
            - No, project-internal file, config, or document → use project type

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

            When a new fact arrives: if the topic already has a wiki page, edit_file to append.
            If the topic is genuinely new, create a new page. Update MEMORY.md index accordingly.

            ### Priority Signals
            | Priority | Criteria | Action |
            |----------|----------|--------|
            | critical | Blocks core functionality, data loss risk, security issue | Extract immediately |
            | high | Affects common workflows, recurring across sessions | Promote to knowledge wiki |
            | medium | Moderate impact, workaround exists | Extract, promote if recurs |
            | low | Minor convenience, edge case | Skip unless patterns emerge |

            ## Rules

            - When updating a wiki page, use edit_file; only use write_file for new pages.
            - If new information semantically overlaps with an existing page, merge — never create duplicates.
            - File names use kebab-case (e.g., `build-system`, `session-management`).

            ## Extraction Procedure

            Read the cursor via `read_extraction_cursor` first; process only messages after it.

            1. **Identify durable knowledge** — scan unprocessed messages for facts matching
               the Detection Signals above.
            2. **Apply Durability Check**: "If I come back in a month, is this still accurate?"
               - **No** — skip, it's transient.
               - **Yes** — proceed to write.
               - **Uncertain** — keep in mind; if it recurs, reconsider.
             3. **Write to wiki pages** at `.core-ai/knowledge/{type}/{name}.md` — use edit_file to
                append, write_file for new pages.
               **Feedback takes priority**: when a contradiction (user correction, test failure,
               error log) conflicts with an existing wiki claim, UPDATE via edit_file — do NOT
               append as a parallel note.
             4. **Update `.core-ai/knowledge/MEMORY.md` index**: add new files with Description
                and When to Use columns, update timestamps.
            5. **Call add_knowledge_log** — record knowledge-layer changes (wiki pages, MEMORY.md).
            6. **Call advance_extraction_cursor** — always call it, whether you extracted or not.
            """;

    private MemoryDirectExtractionSpecs() {
    }
}
