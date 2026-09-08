package ai.core.vectorstore.milvus;

import ai.core.rag.filter.Expression;
import ai.core.rag.filter.ExpressionConverter;

/**
 * @author stephen
 * @deprecated empty-shell filtering DSL; use Milvus native filter strings instead. To be implemented or removed in P2.
 */
@Deprecated
public class MilvusExpressionConverter implements ExpressionConverter {
    @Override
    public String convert(Expression expression) {
        return ExpressionConverter.super.convert(expression);
    }
}
