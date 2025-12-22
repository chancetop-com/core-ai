package ai.core.benchmark.common;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * author: lim chen
 * date: 2025/12/19
 * description:
 */
public class CategoryMapping {
    public static class BFCLCategoryMapping {
        public static final List<String> ALL_AVAILABLE_MEMORY_BACKENDS = List.of(
                "kv", "vector", "rec_sum"
        );

        public static final List<String> NON_LIVE_CATEGORY = List.of(
                "simple_python", "simple_java", "simple_javascript",
                "multiple", "parallel", "parallel_multiple", "irrelevance"
        );

        public static final List<String> LIVE_CATEGORY = List.of(
                "live_simple", "live_multiple", "live_parallel",
                "live_parallel_multiple", "live_irrelevance", "live_relevance"
        );

        public static final List<String> MULTI_TURN_CATEGORY = List.of(
                "multi_turn_base", "multi_turn_miss_func",
                "multi_turn_miss_param", "multi_turn_long_context"
        );

        public static final List<String> WEB_SEARCH_CATEGORY = List.of(
                "web_search_base", "web_search_no_snippet"
        );

        public static final List<String> MEMORY_CATEGORY = ALL_AVAILABLE_MEMORY_BACKENDS.stream()
                .map(backend -> "memory_" + backend)
                .collect(Collectors.toList());

        public static final List<String> MEMORY_SCENARIO_NAME = List.of(
                "student", "customer", "finance", "healthcare", "notetaker"
        );

        public static final List<String> SINGLE_TURN_CATEGORY = combine(NON_LIVE_CATEGORY, LIVE_CATEGORY);

        public static final List<String> AGENTIC_CATEGORY = combine(MEMORY_CATEGORY, WEB_SEARCH_CATEGORY);

        public static final List<String> NON_SCORING_CATEGORY = List.of("format_sensitivity");

        public static final List<String> ALL_SCORING_CATEGORIES = combine(SINGLE_TURN_CATEGORY, MULTI_TURN_CATEGORY, AGENTIC_CATEGORY);

        public static final List<String> ALL_CATEGORIES = combine(ALL_SCORING_CATEGORIES, NON_SCORING_CATEGORY);

        public static final Map<String, List<String>> TEST_COLLECTION_MAPPING = Map.ofEntries(
                Map.entry("all", ALL_CATEGORIES),
                Map.entry("all_scoring", ALL_SCORING_CATEGORIES),
                Map.entry("multi_turn", MULTI_TURN_CATEGORY),
                Map.entry("single_turn", SINGLE_TURN_CATEGORY),
                Map.entry("live", LIVE_CATEGORY),
                Map.entry("non_live", NON_LIVE_CATEGORY),
                Map.entry("non_python", List.of("simple_java", "simple_javascript")),
                Map.entry("python", List.of(
                        "simple_python", "irrelevance", "parallel", "multiple", "parallel_multiple",
                        "live_simple", "live_multiple", "live_parallel", "live_parallel_multiple",
                        "live_irrelevance", "live_relevance"
                )),
                Map.entry("memory", MEMORY_CATEGORY),
                Map.entry("web_search", WEB_SEARCH_CATEGORY),
                Map.entry("agentic", AGENTIC_CATEGORY)
        );


        @SafeVarargs
        @SuppressWarnings("varargs")
        private static List<String> combine(List<String>... lists) {
            return Stream.of(lists)
                    .flatMap(Collection::stream)
                    .toList();
        }
    }

}
