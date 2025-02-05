package ai.core.rag.filter.converter;

import ai.core.rag.filter.Expression;
import ai.core.rag.filter.ExpressionConverter;

/**
 * @author stephen
 */
public class MilvusExpressionConverter implements ExpressionConverter {
    @Override
    public String convert(Expression expression) {
        return ExpressionConverter.super.convert(expression);
    }
}
