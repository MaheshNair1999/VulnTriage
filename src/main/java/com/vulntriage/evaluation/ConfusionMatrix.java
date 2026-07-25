package com.vulntriage.evaluation;

import com.vulntriage.domain.enums.Verdict;

/**
 * 3x3 confusion matrix comparing manual verdicts (rows) vs LLM verdicts (columns).
 *
 * Axes: TP, FP, REVIEW
 * Cell [i][j] = number of findings where manual=i and LLM=j
 *
 * Example:
 *                LLM:TP  LLM:FP  LLM:REVIEW
 *   Manual:TP  [  3       0        1   ]
 *   Manual:FP  [ 13      15       13   ]
 *   Manual:REV [  4       0        0   ]
 */
public class ConfusionMatrix {

    private static final Verdict[] VERDICTS = {Verdict.TP, Verdict.FP, Verdict.REVIEW};

    // matrix[manualIndex][llmIndex]
    private final int[][] matrix = new int[3][3];

    public void increment(Verdict manual, Verdict llm) {
        matrix[indexOf(manual)][indexOf(llm)]++;
    }

    public int get(Verdict manual, Verdict llm) {
        return matrix[indexOf(manual)][indexOf(llm)];
    }

    public int totalManual(Verdict manual) {
        int sum = 0;
        for (int j = 0; j < 3; j++) sum += matrix[indexOf(manual)][j];
        return sum;
    }

    public int totalLlm(Verdict llm) {
        int sum = 0;
        for (int i = 0; i < 3; i++) sum += matrix[i][indexOf(llm)];
        return sum;
    }

    public int total() {
        int sum = 0;
        for (int[] row : matrix) for (int v : row) sum += v;
        return sum;
    }

    public int diagonal() {
        int sum = 0;
        for (int i = 0; i < 3; i++) sum += matrix[i][i];
        return sum;
    }

    private int indexOf(Verdict v) {
        for (int i = 0; i < VERDICTS.length; i++) {
            if (VERDICTS[i] == v) return i;
        }
        throw new IllegalArgumentException("Unknown verdict: " + v);
    }

    @Override
    public String toString() {
        return String.format(
            "ConfusionMatrix%n" +
            "              LLM:TP  LLM:FP  LLM:REV%n" +
            "Manual:TP  [ %5d  %5d  %5d ]%n" +
            "Manual:FP  [ %5d  %5d  %5d ]%n" +
            "Manual:REV [ %5d  %5d  %5d ]",
            get(Verdict.TP,     Verdict.TP),     get(Verdict.TP,     Verdict.FP),     get(Verdict.TP,     Verdict.REVIEW),
            get(Verdict.FP,     Verdict.TP),     get(Verdict.FP,     Verdict.FP),     get(Verdict.FP,     Verdict.REVIEW),
            get(Verdict.REVIEW, Verdict.TP),     get(Verdict.REVIEW, Verdict.FP),     get(Verdict.REVIEW, Verdict.REVIEW)
        );
    }
}
