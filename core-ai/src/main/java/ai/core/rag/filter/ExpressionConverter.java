package ai.core.rag.filter;

/**
 * @author stephen
 */
public interface ExpressionConverter {

    default String convert(Expression expression) {
        return expression.left + symbol(expression) + expression.right;
    }

    default String symbol(Expression expression) {
        return expression.type.getSymbol();
    }

}
