package org.jline.reader.impl;

/**
 * Bridge to access LineReaderImpl's protected post field.
 * <p>
 * JLine 3 has no public API to clear the completion post area.
 * LIST_CHOICES sets post but never clears it when candidates are empty.
 * COMPLETE_WORD/EXPAND_OR_COMPLETE clear post but have side effects (auto-fill, insertTab).
 * Same-package access to the protected field is the only clean, side-effect-free approach.
 *
 * @author xander
 */
public class CompletionHelper {

    public static void refreshCompletion(LineReaderImpl reader) {
        reader.post = null;
        reader.listChoices();
    }
}
