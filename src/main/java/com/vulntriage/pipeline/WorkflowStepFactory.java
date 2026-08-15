package com.vulntriage.pipeline;

import com.vulntriage.app.AppContext;
import com.vulntriage.domain.PromptTemplate;
import com.vulntriage.domain.WorkflowStep;
import com.vulntriage.event.PipelineObserver;
import com.vulntriage.pipeline.api.PipelineStage;
import com.vulntriage.pipeline.stages.*;
import com.vulntriage.triage.api.TriageStrategy;
import com.vulntriage.triage.mock.MockTriageStrategy;
import com.vulntriage.triage.ollama.OllamaTriageStrategy;

import java.util.List;

/**
 * Factory pattern — creates the correct PipelineStage implementation
 * for a given WorkflowStep definition.
 *
 * This is the bridge between the user-defined workflow (parsed JSON)
 * and the executable pipeline (chain of PipelineStage objects).
 *
 * Adding a new step type requires:
 *   1. Adding the type to WorkflowStep.StepType enum
 *   2. Writing a class that implements PipelineStage
 *   3. Adding one case to create() — no other code changes needed
 *
 * Step parameter reference:
 *
 *   SCAN:
 *     scanner  — "semgrep" or "trivy" (required)
 *     ruleset  — Semgrep ruleset, default "p/security-audit"
 *     timeout  — seconds, default 300
 *
 *   SELECT:
 *     scanner      — scanner name to filter by, or "all" (default)
 *     repository   — exact repo name to filter by, or "all" (default)
 *     rule_pattern — substring match against rule ID (case-insensitive); empty = all
 *     severity     — comma-separated: ERROR, WARNING, INFO; or "all" (default)
 *
 *   FILTER:
 *     condition — filter expression string (required)
 *
 *   SAMPLE:
 *     size     — target sample size, default 1000
 *
 *   TRIAGE:
 *     model    — Ollama model name, default from AppContext
 *     url      — Ollama URL, default from AppContext
 *     run_name — name for the evaluation run
 *
 *   SCORE:
 *     (no required parameters)
 *
 *   REPORT:
 *     formats  — comma-separated list: "json", "csv"
 */
public class WorkflowStepFactory {

    private final List<PipelineObserver> observers;
    private final AppContext             ctx;

    public WorkflowStepFactory(List<PipelineObserver> observers) {
        this.observers = observers;
        this.ctx       = AppContext.getInstance();
    }

    /**
     * Create a PipelineStage from a WorkflowStep definition.
     *
     * @param step the workflow step definition
     * @return a ready-to-execute PipelineStage
     * @throws IllegalArgumentException if the step type is unrecognised
     */
    public PipelineStage create(WorkflowStep step) {
        return switch (step.getType()) {

            case SCAN -> {
                String scanner = step.param("scanner", "semgrep").trim().toLowerCase();
                com.vulntriage.config.ScanConfig scanCfg = switch (scanner) {
                    case "semgrep"   -> com.vulntriage.config.ScanConfig.semgrepOnly();
                    case "trivy"     -> com.vulntriage.config.ScanConfig.trivyOnly();
                    case "gitleaks"  -> com.vulntriage.config.ScanConfig.gitleaksOnly();
                    case "codeql"    -> com.vulntriage.config.ScanConfig.codeqlOnly();
                    case "sonarqube" -> com.vulntriage.config.ScanConfig.sonarqubeOnly(
                        step.param("sonar_url", ""),
                        step.param("sonar_token", ""));
                    default          -> com.vulntriage.config.ScanConfig.defaults();
                };
                String ruleset = step.param("ruleset", "");
                if (!ruleset.isBlank()) scanCfg.setSemgrepRuleset(ruleset);
                String timeout = step.param("timeout", "");
                if (!timeout.isBlank()) scanCfg.setTimeoutSeconds(Integer.parseInt(timeout));
                yield new ScanStage(observers, ctx.scanRunRepo(), ctx.findingRepo(), scanCfg);
            }

            case SELECT -> new SelectStage(
                observers,
                step.param("scanner",      "all"),
                step.param("repository",   "all"),
                step.param("rule_pattern", ""),
                step.param("severity",     "all")
            );

            case FILTER -> new FilterStage(
                observers,
                step.param("condition", "severity >= INFO") // default: pass everything
            );

            case SAMPLE -> new SampleStage(
                observers,
                Integer.parseInt(step.param("size", "1000"))
            );

            case TRIAGE -> {
                String promptVer = step.param("prompt_version", "v1.0");
                PromptTemplate tmpl = ctx.promptTemplateRepo()
                    .findByVersion(promptVer)
                    .orElseGet(() -> ctx.promptTemplateRepo()
                        .findByVersion("v1.0")
                        .orElse(null));
                String templateStr = tmpl != null ? tmpl.getTemplate() : null;
                String templateVer = tmpl != null ? tmpl.getVersion()  : promptVer;
                yield new TriageStage(
                    observers,
                    buildTriageStrategy(step),
                    ctx.evalRepo(),
                    ctx.llmRepo(),
                    step.param("run_name", "Workflow Run"),
                    com.vulntriage.config.AppConfig.getInstance().getTriageDelayMs(),
                    templateStr,
                    templateVer
                );
            }

            case SCORE -> new ScoreStage(
                observers,
                ctx.llmRepo(),
                ctx.findingRepo()
            );

            case REPORT -> new EvaluateStage(
                observers,
                ctx.evalRepo(),
                ctx.llmRepo(),
                ctx.reviewRepo()
            );
        };
    }

    /**
     * Build the appropriate triage strategy for a TRIAGE step.
     * Falls back to MockTriageStrategy if Ollama is not configured.
     */
    private TriageStrategy buildTriageStrategy(WorkflowStep step) {
        String url   = step.param("url",   ctx.getOllamaUrl());
        String model = step.param("model", ctx.getOllamaModel());

        if (url.isBlank() || model.isBlank()) {
            return new MockTriageStrategy();
        }

        OllamaTriageStrategy strategy = new OllamaTriageStrategy(url, model);
        if (strategy.isAvailable()) {
            return strategy;
        }

        // Fall back to mock if Ollama is not reachable at runtime
        return new MockTriageStrategy();
    }
}
