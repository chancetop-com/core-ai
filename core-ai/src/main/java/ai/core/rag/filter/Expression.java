package ai.core.rag.filter;

/**
 * @author stephen
 */
public class Expression implements Operand {
    public ExpressionType type;
    public Operand left;
    public Operand right;
}
