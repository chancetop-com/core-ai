package ai.core.document;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;


public class Tokenizer {
    public static final EncodingType DEFAULT_ENCODING_TYPE = EncodingType.CL100K_BASE;

    // Lazy-loaded encoding registry - expensive to create, initialized during warmup
    private static volatile EncodingRegistry encodingRegistry;

    // Cache encodings by type to avoid repeated lookups
    private static final ConcurrentMap<EncodingType, Encoding> ENCODING_CACHE = new ConcurrentHashMap<>();

    private static EncodingRegistry getRegistry() {
        if (encodingRegistry == null) {
            synchronized (Tokenizer.class) {
                if (encodingRegistry == null) {
                    encodingRegistry = Encodings.newLazyEncodingRegistry();
                }
            }
        }
        return encodingRegistry;
    }

    private static Encoding getEncoding(EncodingType type) {
        return ENCODING_CACHE.computeIfAbsent(type, getRegistry()::getEncoding);
    }

    public static int tokenCount(String text, EncodingType type) {
        return getEncoding(type).countTokens(text);
    }

    public static int tokenCount(String text) {
        return tokenCount(text, DEFAULT_ENCODING_TYPE);
    }

    public static List<Integer> encode(String text, EncodingType type) {
        return getEncoding(type).encode(text).boxed();
    }

    public static List<Integer> encode(String text) {
        return encode(text, DEFAULT_ENCODING_TYPE);
    }

    public static String decode(List<Integer> encoded, EncodingType type) {
        var intArrayList = new IntArrayList();
        encoded.forEach(intArrayList::add);
        return getEncoding(type).decode(intArrayList);
    }

    public static String decode(List<Integer> encoded) {
        return decode(encoded, DEFAULT_ENCODING_TYPE);
    }

    public static String truncate(String text, int maxTokens) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        List<Integer> tokens = encode(text);
        if (tokens.size() <= maxTokens) {
            return text;
        }
        return decode(tokens.subList(0, maxTokens));
    }

    /**
     * Preload the default encoding to avoid slow first-time tokenization.
     */
    public static void warmup() {
        getEncoding(DEFAULT_ENCODING_TYPE);
    }
}
