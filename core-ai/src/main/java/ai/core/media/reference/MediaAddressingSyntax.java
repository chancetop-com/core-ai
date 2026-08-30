package ai.core.media.reference;

import java.util.Locale;

/**
 * How a model family addresses its reference assets inside the prompt. The token-to-asset binding is
 * positional (the arrays are the binding), so the token generator and the array builder must agree.
 * {@code NONE} is for families with no addressing syntax at all — their prompts must have the tokens
 * removed rather than passed through.
 *
 * @author stephen
 */
public enum MediaAddressingSyntax {
    // @Image1 / @Video1 (Seedance 2.5)
    AT_TOKEN,
    // [Image1] / [Video1]
    BRACKET,
    // <Picture 1> / <Video 1> (MiniMax H3)
    ANGLE_SUBJECT,
    // no addressing syntax (Seedream image-to-image); tokens are stripped from the prompt
    NONE;

    public static MediaAddressingSyntax parse(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
