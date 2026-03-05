package ai.core.benchmark;

import ai.core.benchmark.common.BFCLCategory;
import ai.core.benchmark.evaluator.BFCLEvaluator;
import ai.core.benchmark.inference.BFCLInferenceFCHandle;
import ai.core.benchmark.inference.BFCLInferencePlanHandle;
import ai.core.llm.LLMProviders;
import core.framework.inject.Inject;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * author: lim chen
 * date: 2025/12/22
 * description:
 */
@Disabled
class BFCLTest extends IntegrationTest {
    private final Logger logger = LoggerFactory.getLogger(BFCLTest.class);
    @Inject
    LLMProviders llmProviders;


    @Test
    void test2() {
        var eval = new BFCLEvaluator(new BFCLInferenceFCHandle(llmProviders.getProvider()), "gpt-5-nano-2025-08-07-FC");
//        eval.eval(BFCLCategory.NON_LIVE, List.of("parallel_0"));
        eval.eval(BFCLCategory.LIVE, List.of());

    }
    @Test
    void test3() {
        var eval = new BFCLEvaluator(new BFCLInferencePlanHandle(llmProviders.getProvider()), "gpt-5-nano-2025-08-07-FC");
//        eval.eval(BFCLCategory.NON_LIVE, List.of("parallel_0"));
//        eval.eval(BFCLCategory.LIVE, List.of("live_simple_61-29-1"));
//        eval.eval(BFCLCategory.LIVE, List.of());
        eval.eval(BFCLCategory.NON_LIVE, List.of());

    }


}
