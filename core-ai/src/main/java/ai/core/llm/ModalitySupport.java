package ai.core.llm;

/**
 * Three-valued capability verdict: downgrade only on positive evidence of non-support,
 * pass through on UNKNOWN so unannotated vision models are never silently degraded.
 *
 * @author Xander
 */
public enum ModalitySupport {
    SUPPORTED,
    UNSUPPORTED,
    UNKNOWN
}
