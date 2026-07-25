package com.vulntriage.scoring;

import java.util.List;
import java.util.Map;

/**
 * Public API for the arithmetic formula engine.
 *
 * A formula is a string combining the variables:
 *   severity         — severity weight (ERROR=1.0, WARNING=0.6, INFO=0.2)
 *   category_weight  — vulnerability category weight (0.5 – 1.0)
 *   confidence       — normalised LLM confidence (0.0 – 1.0)
 *   recurrence       — recurrence factor (1.0 + 0.1 per extra scan run)
 *
 * Example formula:
 *   "severity * category_weight * (1 + 0.3 * confidence) * recurrence"
 *
 * Usage:
 *   FormulaEngine engine = new FormulaEngine();
 *   FormulaNode   tree   = engine.compile(formulaString);
 *   double        score  = engine.evaluate(tree, variableMap);
 */
public class FormulaEngine {

    private final FormulaTokenizer tokenizer = new FormulaTokenizer();
    private final FormulaParser    parser    = new FormulaParser();

    /**
     * Compile a formula string into a reusable AST.
     * Throws FormulaException if the formula is syntactically invalid.
     */
    public FormulaNode compile(String formula) {
        if (formula == null || formula.isBlank())
            throw new FormulaException("Formula cannot be empty");
        List<FormulaToken> tokens = tokenizer.tokenize(formula);
        return parser.parse(tokens);
    }

    /**
     * Evaluate a compiled formula AST with the given variable bindings.
     * Throws FormulaException if a variable in the AST is missing from the map.
     */
    public double evaluate(FormulaNode tree, Map<String, Double> variables) {
        return tree.evaluate(variables);
    }

    /**
     * Convenience method: compile and evaluate in one step.
     */
    public double evaluate(String formula, Map<String, Double> variables) {
        return evaluate(compile(formula), variables);
    }

    /**
     * Returns true if the formula is syntactically valid (no unknown variables checked).
     */
    public boolean isValid(String formula) {
        try { compile(formula); return true; }
        catch (FormulaException e) { return false; }
    }

    /**
     * Returns a human-readable description of the compiled formula tree.
     */
    public String describe(FormulaNode tree) {
        return tree.describe();
    }
}
