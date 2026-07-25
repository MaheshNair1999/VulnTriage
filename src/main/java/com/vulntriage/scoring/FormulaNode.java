package com.vulntriage.scoring;

import java.util.Map;

/**
 * A node in the arithmetic expression AST produced by FormulaParser.
 * Each node evaluates itself given a map of variable name → double value.
 */
public interface FormulaNode {
    double evaluate(Map<String, Double> variables);
    String describe();
}
