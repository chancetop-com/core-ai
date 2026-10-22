package ai.core.cli.agent;

import ai.core.agent.budget.RunBudget;
import ai.core.prompt.PromptInject;

/**
 * Static budget policy injected into the system prompt. It deliberately contains
 * no numbers: the dynamic block lives only in messages[0] and is refreshed by
 * {@link RunBudgetLifecycle}, so memory reloads cannot resurrect stale values.
 *
 * @author stephen
 */
public final class RunBudgetPrompt implements PromptInject {
    private static final String STATIC_POLICY = """
            The dynamic <run_budget> block gives the current budget level; its level is NORMAL, CONSERVE,
            URGENT, FINAL, or EXPIRED. The level and its directive are authoritative. Live remaining values
            are printed in text tool results as a [run budget] line: trust that line over the block.
            binding names the dimension closest to its limit.
            A complete deliverable on disk is worth more than a better plan when the budget is tight.
            - Create or finish every required file before optional exploration, refactoring, or extra features.
            - At CONSERVE or worse, if you already know the complete content of a file (you wrote it in this run
              or read all of it), write it in ONE write_file call. Otherwise use edit_file; read_file returns at
              most 2000 lines, so never rewrite a large existing file from a partial read.
            - Run the shortest meaningful verification as soon as a minimal end-to-end version exists
              (syntax check, one command, one test, one sample file). Do not wait for a perfect implementation.
            - Do not re-read a file unless it changed since your last read (you edited it, or a command may have
              changed it). Do not paste large tool output into the conversation. Prefer grep with a limit,
              read_file with offset/limit, and pass save_to_file=<path> to a tool that returns a large result.
            - Do not start an install, build, download, or long command that cannot finish before the remaining
              budget. A package install that timed out may have partially completed: check whether it actually
              finished before retrying it.
            - Set command timeouts to at most the remaining time minus the reserve from the latest [run budget]
              line. The runtime may lower them further; if it does, the tool result says so. Treat a timeout or
              a TIME_BUDGET_EXHAUSTED rejection as a budget signal, not an infrastructure failure.
            - If work is independent and parallelizable, use task(...) with run_in_background=true. Do not launch
              more than 3 subagents at once. Do not launch subagents when the budget is URGENT or FINAL, and if
              you are yourself a subagent, do not spawn further subagents: complete your assigned task.
            - When the budget says CONSERVE: start converging; finish the required files and verify them.
            - When the budget says URGENT: stop exploring and refactoring. Only finish and verify the required
              deliverable. No new installs, no new subagents, no large outputs.
            - When the budget says FINAL: do not start new work. Finish only a missing required file, then stop
              tool use and reply with what was created and what is still missing.
            - When the budget says EXPIRED: do not call tools; answer with the current state.
            """;

    private final RunBudget budget;

    public RunBudgetPrompt(RunBudget budget) {
        this.budget = budget;
    }

    @Override
    public String inject() {
        return budget == null ? "" : STATIC_POLICY;
    }

    @Override
    public SectionType type() {
        return SectionType.ENVIRONMENT;
    }
}
