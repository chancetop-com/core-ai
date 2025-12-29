package ai.core.benchmark.common;

import java.util.List;
import java.util.stream.Stream;

/**
 * author: lim chen
 * date: 2025/12/26
 * description:
 */
public enum BFCLCategory {

    NON_LIVE_CATEGORY(List.of(
            "simple_python",
            "simple_java",
            "simple_javascript",
            "multiple",
            "parallel",
            "parallel_multiple",
            "irrelevance"
    )),
    LIVE_CATEGORY(List.of(
            "live_simple",
            "live_multiple",
            "live_parallel",
            "live_parallel_multiple",
            "live_irrelevance",
            "live_relevance"
    )),
    NON_PYTHON(List.of("simple_java", "simple_javascript")),
    PYTHON(List.of(
            "simple_python",
            "irrelevance",
            "parallel",
            "multiple",
            "parallel_multiple",
            "live_simple",
            "live_multiple",
            "live_parallel",
            "live_parallel_multiple",
            "live_irrelevance",
            "live_relevance"
    )),

    SINGLE_TURN_CATEGORY(combine(NON_LIVE_CATEGORY, LIVE_CATEGORY));


    private final List<String> types;

    BFCLCategory(List<String> types) {
        this.types = types;
    }

    public List<String> getTypes() {
        return types;
    }

    private static List<String> combine(BFCLCategory... categories) {
        return Stream.of(categories)
                .flatMap(c -> c.types.stream())
                .toList();
    }
}
