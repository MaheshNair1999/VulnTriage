package com.vulntriage.scoring;

public class FormulaToken {

    private final FormulaTokenType type;
    private final String           value;

    public FormulaToken(FormulaTokenType type, String value) {
        this.type  = type;
        this.value = value;
    }

    public FormulaTokenType getType()  { return type;  }
    public String           getValue() { return value; }

    @Override
    public String toString() { return type + "(" + value + ")"; }
}
