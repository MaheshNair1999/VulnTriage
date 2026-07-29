package com.vulntriage.evaluation;

/**
 * Holds all computed evaluation metrics for one evaluation run.
 * Produced by MetricsCalculator, displayed in the Evaluation screen.
 */
public class EvaluationReport {

    private long            evaluationRunId;
    private String          runName;
    private int             totalCompared;    // findings with both manual + LLM verdict
    private ConfusionMatrix confusionMatrix;

    // ── Key metrics (all 0.0-1.0 range) ───────────────────────────────────
    private double accuracy;          // (TP+FP+REV correctly predicted) / total
    private double tpRecall;          // TP caught by LLM / total manual TP
    private double tpPrecision;       // LLM TP correct / total LLM TP predictions
    private double fpAgreement;       // LLM FP correct / total manual FP
    private double reviewAgreement;   // LLM REVIEW correct / total manual REVIEW
    private double falseNegativeRate; // manual TP dismissed as FP by LLM / total manual TP
    private double falsePositiveRate; // manual FP escalated as TP by LLM / total manual FP

    // ── Getters & Setters ──────────────────────────────────────────────────

    public long            getEvaluationRunId()           { return evaluationRunId; }
    public void            setEvaluationRunId(long id)    { this.evaluationRunId = id; }

    public String          getRunName()                   { return runName; }
    public void            setRunName(String n)           { this.runName = n; }

    public int             getTotalCompared()             { return totalCompared; }
    public void            setTotalCompared(int t)        { this.totalCompared = t; }

    public ConfusionMatrix getConfusionMatrix()           { return confusionMatrix; }
    public void            setConfusionMatrix(ConfusionMatrix m) { this.confusionMatrix = m; }

    public double          getAccuracy()                  { return accuracy; }
    public void            setAccuracy(double v)          { this.accuracy = v; }

    public double          getTpRecall()                  { return tpRecall; }
    public void            setTpRecall(double v)          { this.tpRecall = v; }

    public double          getTpPrecision()               { return tpPrecision; }
    public void            setTpPrecision(double v)       { this.tpPrecision = v; }

    public double          getFpAgreement()               { return fpAgreement; }
    public void            setFpAgreement(double v)       { this.fpAgreement = v; }

    public double          getReviewAgreement()           { return reviewAgreement; }
    public void            setReviewAgreement(double v)   { this.reviewAgreement = v; }

    public double          getFalseNegativeRate()         { return falseNegativeRate; }
    public void            setFalseNegativeRate(double v) { this.falseNegativeRate = v; }

    public double          getFalsePositiveRate()         { return falsePositiveRate; }
    public void            setFalsePositiveRate(double v) { this.falsePositiveRate = v; }
}
