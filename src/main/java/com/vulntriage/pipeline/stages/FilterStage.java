package com.vulntriage.pipeline.stages;

import com.vulntriage.domain.Finding;
import com.vulntriage.event.PipelineObserver;
import com.vulntriage.filter.Expression;
import com.vulntriage.filter.FilterException;
import com.vulntriage.filter.FilterRuleEngine;
import com.vulntriage.pipeline.api.AbstractPipelineStage;
import com.vulntriage.pipeline.api.PipelineContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Pipeline stage that filters findings using the Filter Rule Engine.
 *
 * The condition is compiled into an AST once at stage construction,
 * then evaluated against every finding in ctx.getAllFindings().
 * Only findings for which the expression evaluates to true are kept.
 *
 * Example conditions:
 *   "severity >= WARNING"
 *   "severity >= WARNING AND category = xss"
 *   "(category = xss OR category = sql_injection) AND NOT source = TRIVY"
 *
 * Placed in the pipeline after SCAN and before SAMPLE, TRIAGE.
 */
public class FilterStage extends AbstractPipelineStage {

    private static final Logger log = LoggerFactory.getLogger(FilterStage.class);

    private final Expression       compiledExpression;
    private final FilterRuleEngine engine;
    private final String           conditionText;

    /**
     * @param observers  pipeline event observers
     * @param condition  filter expression string — compiled immediately at construction
     * @throws FilterException if the condition is syntactically invalid
     */
    public FilterStage(List<PipelineObserver> observers, String condition) {
        super(observers);
        this.engine          = new FilterRuleEngine();
        this.conditionText   = condition;
        this.compiledExpression = engine.compile(condition); // fail fast on bad expression
    }

    @Override
    public String getName() { return "Filter"; }

    @Override
    protected int progressAfter() { return 35; }

    @Override
    protected void doExecute(PipelineContext ctx) {
        List<Finding> before = ctx.getAllFindings();
        if (before.isEmpty()) {
            log.warn("FilterStage: no findings to filter");
            return;
        }

        List<Finding> after = engine.apply(before, compiledExpression);

        log.info("FilterStage: {} → {} findings after applying: {}",
            before.size(), after.size(), conditionText);

        ctx.setAllFindings(after);
    }
}
