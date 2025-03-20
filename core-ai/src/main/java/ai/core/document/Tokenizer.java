package ai.core.document;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;

import java.util.List;

/**
 * @author stephen
 */
public class Tokenizer {
    public static final EncodingType DEFAULT_ENCODING_TYPE = EncodingType.CL100K_BASE;

    public static int tokenCount(String text, EncodingType type) {
        return Encodings.newDefaultEncodingRegistry().getEncoding(type).countTokens(text);
    }

    public static int tokenCount(String text) {
        return tokenCount(text, DEFAULT_ENCODING_TYPE);
    }

    public static List<Integer> encode(String text, EncodingType type) {
        return Encodings.newDefaultEncodingRegistry().getEncoding(type).encode(text).boxed();
    }

    public static List<Integer> encode(String text) {
        return encode(text, DEFAULT_ENCODING_TYPE);
    }

    public static String decode(List<Integer> encoded, EncodingType type) {
        var intArrayList = new IntArrayList();
        encoded.forEach(intArrayList::add);
        return Encodings.newDefaultEncodingRegistry().getEncoding(type).decode(intArrayList);
    }

    public static String decode(List<Integer> encoded) {
        return decode(encoded, DEFAULT_ENCODING_TYPE);
    }
}
