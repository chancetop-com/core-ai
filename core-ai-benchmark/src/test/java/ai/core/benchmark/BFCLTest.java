package ai.core.benchmark;

import ai.core.benchmark.common.BFCLCategory;
import ai.core.benchmark.evaluator.BFCLEvaluator;
import ai.core.benchmark.inference.BFCLInferenceFCHandle;
import ai.core.benchmark.loader.BFCLDatasetLoader;
import ai.core.llm.LLMProviders;
import core.framework.inject.Inject;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * author: lim chen
 * date: 2025/12/22
 * description:
 */
public class BFCLTest extends IntegrationTest {
    private final Logger logger = LoggerFactory.getLogger(BFCLTest.class);
    @Inject
    LLMProviders llmProviders;


    @Test
    void test2() {
        var eval = new BFCLEvaluator(new BFCLInferenceFCHandle(llmProviders.getProvider()));
        eval.eval(BFCLCategory.NON_PYTHON);

    }
}
