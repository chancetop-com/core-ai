package ai.core.rag.filter;

/**
 * @author stephen
 * @deprecated empty-shell filtering DSL; use backend native filter strings instead. To be implemented or removed in P2.
 */
@Deprecated
public enum ExpressionType {
    AND(" AND "),
    OR(" OR "),
    EQ(" = "),
    NE(" != "),
    GT(" > "),
    GTE(" >= "),
    LE(" < "),
    LTE(" <= "),
    IN(" IN "),
    MIN(" MIN "),
    NOT(" NOT "),
    AND_NOT(" AND NOT "),
    OR_NOT(" OR NOT ");

    private final String symbol;

    ExpressionType(String symbol) {
        this.symbol = symbol;
    }

    public String getSymbol() {
        return symbol;
    }
}
