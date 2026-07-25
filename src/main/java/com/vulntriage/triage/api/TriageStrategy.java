package com.vulntriage.triage.api;

import com.vulntriage.domain.Finding;

/**
 * Strategy pattern — pluggable LLM backend for triage.
 *
 * Implementations:
 *   - OllamaTriageStrategy  — calls local Ollama (Qwen3:8B, Llama, etc.)
 *   - MockTriageStrategy    — deterministic responses for testing
 *
 * Future implementations (no pipeline changes needed):
 *   - OpenAiTriageStrategy
 *   - ClaudeTriageStrategy
 *   - RuleBasedTriageStrategy (fallback when no LLM available)
 */
public interface TriageStrategy {

    /**
     * Analyse a finding and return a verdict with reasoning.
     *
     * @param finding the finding to triage
     * @return triage result with verdict, confidence, reasoning, remediation
     * @throws TriageException if the backend is unreachable or returns invalid output
     */
    TriageResult triage(Finding finding);

    /** Human-readable model name, e.g. "qwen3:8b". Used in LlmResult.modelUsed. */
    String getModelName();

    /** Check whether the backend is reachable before starting a batch triage. */
    boolean isAvailable();
}
