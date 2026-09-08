package ai.core.rag.filter;

/**
 * @author stephen
 * @deprecated empty-shell filtering DSL; use backend native filter strings instead. To be implemented or removed in P2.
 */
@Deprecated
public interface ExpressionConverter {

    default String convert(Expression expression) {
        return expression.left + symbol(expression) + expression.right;
    }

    default String symbol(Expression expression) {
        return expression.type.getSymbol();
    }

}
