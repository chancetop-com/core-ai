package ai.core.rag.filter;

/**
 * @author stephen
 */
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
