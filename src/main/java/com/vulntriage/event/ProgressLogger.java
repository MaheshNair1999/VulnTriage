package com.vulntriage.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Observer that logs all pipeline events via SLF4J.
 * Always registered — gives a permanent audit trail in the log file.
 */
public class ProgressLogger implements PipelineObserver {

    private static final Logger log = LoggerFactory.getLogger(ProgressLogger.class);

    @Override
    public void onEvent(PipelineEvent event) {
        switch (event.getType()) {
            case PIPELINE_STARTED   -> log.info("Pipeline started: {}", event.getMessage());
            case STAGE_STARTED      -> log.info("  → {}", event.getMessage());
            case STAGE_COMPLETED    -> log.info("  ✓ {}", event.getMessage());
            case FINDING_PROCESSED  -> log.debug("  {}", event.getMessage());
            case PIPELINE_COMPLETE  -> log.info("Pipeline complete: {}", event.getMessage());
            case PIPELINE_FAILED    -> log.error("Pipeline failed: {}", event.getMessage());
        }
    }
}
