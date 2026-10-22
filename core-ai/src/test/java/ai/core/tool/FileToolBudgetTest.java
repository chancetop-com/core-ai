package ai.core.tool;

import ai.core.agent.ExecutionContext;
import ai.core.agent.budget.BudgetClass;
import ai.core.tool.tools.EditFileTool;
import ai.core.tool.tools.HashEditFileTool;
import ai.core.tool.tools.HashReadFileTool;
import ai.core.tool.tools.ReadFileTool;
import ai.core.tool.tools.WriteFileTool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author stephen
 */
class FileToolBudgetTest {
    @Test
    void fileToolsUseTheCheapBudgetClass() {
        var context = ExecutionContext.empty();
        assertEquals(BudgetClass.CHEAP, new ReadFileTool.Builder().build()
                .budgetProfile("{}", context).budgetClass());
        assertEquals(BudgetClass.CHEAP, new WriteFileTool.Builder().build()
                .budgetProfile("{}", context).budgetClass());
        assertEquals(BudgetClass.CHEAP, new EditFileTool.Builder().build()
                .budgetProfile("{}", context).budgetClass());
        assertEquals(BudgetClass.CHEAP, new HashReadFileTool.Builder().build()
                .budgetProfile("{}", context).budgetClass());
        assertEquals(BudgetClass.CHEAP, new HashEditFileTool.Builder().build()
                .budgetProfile("{}", context).budgetClass());
    }
}
