package com.vulntriage.pipeline.stages;

import com.vulntriage.event.PipelineObserver;
import com.vulntriage.pipeline.api.AbstractPipelineStage;
import com.vulntriage.pipeline.api.PipelineContext;
import com.vulntriage.sampling.StratifiedSampler;
import com.vulntriage.domain.Finding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Stage 2: Draw a stratified random sample from all findings.
 *
 * Reads ctx.allFindings, writes ctx.sample.
 * Sample size is configurable — default 1000 for thesis, 49 for internship demo.
 */
public class SampleStage extends AbstractPipelineStage {

    private static final Logger log = LoggerFactory.getLogger(SampleStage.class);

    private final int             targetSampleSize;
    private final StratifiedSampler sampler;

    public SampleStage(List<PipelineObserver> observers, int targetSampleSize) {
        super(observers);
        this.targetSampleSize = targetSampleSize;
        this.sampler          = new StratifiedSampler();
    }

    @Override
    public String getName() { return "Sample"; }

    @Override
    protected int progressAfter() { return 40; }

    @Override
    protected void doExecute(PipelineContext ctx) {
        List<Finding> all = ctx.getAllFindings();

        if (all.isEmpty()) {
            log.warn("SampleStage: no findings to sample from");
            ctx.setSample(List.of());
            return;
        }

        List<Finding> sample = sampler.sample(all, targetSampleSize);

        log.info("SampleStage: {} findings → {} sample (target {})",
            all.size(), sample.size(), targetSampleSize);

        ctx.setSample(sample);
    }
}
