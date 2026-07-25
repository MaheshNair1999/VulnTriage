package com.vulntriage.triage.ollama;

import com.vulntriage.domain.Finding;
import com.vulntriage.triage.api.TriageException;
import com.vulntriage.triage.api.TriageResult;
import com.vulntriage.triage.api.TriageStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Strategy pattern implementation — LLM triage via local Ollama.
 *
 * Default configuration: http://localhost:11434, model qwen3:8b
 * Both are configurable via the constructor for flexibility.
 *
 * Retry logic: if the LLM response cannot be parsed as valid JSON
 * (Qwen3 occasionally adds prose), one retry is attempted with a
 * stricter prompt that emphasises JSON-only output.
 */
public class OllamaTriageStrategy implements TriageStrategy {

    private static final Logger log = LoggerFactory.getLogger(OllamaTriageStrategy.class);

    public static final String DEFAULT_URL   = "http://localhost:11434";
    public static final String DEFAULT_MODEL = "qwen3:8b";

    private static final int MAX_RETRIES      = 2;
    private static final int TIMEOUT_SECONDS  = 300; // 5 minutes — model load on first call can be slow

    private final OllamaClient         client;
    private final PromptBuilder        promptBuilder;
    private final OllamaResponseParser responseParser;

    /** Default constructor — uses localhost:11434 and qwen3:8b */
    public OllamaTriageStrategy() {
        this(DEFAULT_URL, DEFAULT_MODEL);
    }

    public OllamaTriageStrategy(String baseUrl, String model) {
        this.client         = new OllamaClient(baseUrl, model);
        this.promptBuilder  = new PromptBuilder();
        this.responseParser = new OllamaResponseParser();
    }

    /** Constructor for testing with injected dependencies */
    public OllamaTriageStrategy(OllamaClient client,
                         PromptBuilder promptBuilder,
                         OllamaResponseParser responseParser) {
        this.client         = client;
        this.promptBuilder  = promptBuilder;
        this.responseParser = responseParser;
    }

    @Override
    public TriageResult triage(Finding finding) {
        String prompt = promptBuilder.build(finding);

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                String rawResponse = client.generate(prompt, TIMEOUT_SECONDS);
                TriageResult result = responseParser.parse(rawResponse, PromptBuilder.PROMPT_VERSION);
                log.debug("Triage success (attempt {}): finding={}, verdict={}, confidence={}",
                    attempt, finding.getId(), result.getVerdict(), result.getConfidence());
                return result;

            } catch (TriageException e) {
                if (attempt == MAX_RETRIES) {
                    log.error("Triage failed after {} attempts for finding id={}: {}",
                        MAX_RETRIES, finding.getId(), e.getMessage());
                    throw e;
                }
                log.warn("Triage attempt {} failed, retrying: {}", attempt, e.getMessage());
                // Add strict JSON reminder on retry
                prompt = addJsonReminder(prompt);
            }
        }

        // Unreachable — loop always returns or throws
        throw new TriageException("Triage failed unexpectedly");
    }

    @Override
    public String getModelName() {
        return client.getModel();
    }

    @Override
    public boolean isAvailable() {
        if (!client.isReachable()) {
            log.warn("Ollama not reachable at {}. Is it running?", client.getBaseUrl());
            return false;
        }
        if (!client.isModelAvailable()) {
            log.warn("Model '{}' not found in Ollama. Run: ollama pull {}",
                getModelName(), getModelName());
            return false;
        }
        return true;
    }

    /**
     * Appends a stricter JSON reminder to the prompt on retry.
     * Helps when the model ignored the JSON-only instruction on first attempt.
     */
    private String addJsonReminder(String originalPrompt) {
        return originalPrompt + """

            IMPORTANT: Your previous response could not be parsed.
            You MUST return ONLY a valid JSON object.
            Do NOT include any text before or after the JSON.
            Do NOT use markdown code blocks.
            Start your response with { and end with }.
            """;
    }
}
