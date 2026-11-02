package ai.core.session;

/**
 * Outcome of reading a persisted session snapshot. {@link #MISSING} means the file does not exist;
 * an existing but empty file is {@link #CORRUPT}.
 *
 * @author stephen
 */
public enum SessionLoadState {
    OK,
    MISSING,
    CORRUPT,
    NEWER_VERSION,
    IO_ERROR
}
