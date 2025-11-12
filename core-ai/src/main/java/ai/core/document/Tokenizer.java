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

    // Cache the encoding registry - this is expensive to create
    private static final EncodingRegistry ENCODING_REGISTRY = Encodings.newDefaultEncodingRegistry();

    // Cache encodings by type to avoid repeated lookups
    private static final ConcurrentMap<EncodingType, Encoding> ENCODING_CACHE = new ConcurrentHashMap<>();

    private static Encoding getEncoding(EncodingType type) {
        return ENCODING_CACHE.computeIfAbsent(type, ENCODING_REGISTRY::getEncoding);
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
}
