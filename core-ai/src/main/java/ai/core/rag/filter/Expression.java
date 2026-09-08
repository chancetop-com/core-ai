package ai.core.rag.filter;

/**
 * @author stephen
 * @deprecated empty-shell filtering DSL; use backend native filter strings (e.g. {@code SimilaritySearchRequest.filter}) instead. To be implemented or removed in P2.
 */
@Deprecated
public class Expression implements Operand {
    public ExpressionType type;
    public Operand left;
    public Operand right;
}
