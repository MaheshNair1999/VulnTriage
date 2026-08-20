package com.vulntriage.triage.cloud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vulntriage.domain.Finding;
import com.vulntriage.triage.api.TriageException;
import com.vulntriage.triage.api.TriageResult;
import com.vulntriage.triage.api.TriageStrategy;
import com.vulntriage.triage.ollama.OllamaResponseParser;
import com.vulntriage.triage.ollama.PromptBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * TriageStrategy implementation for cloud LLM providers:
 * OpenAI (GPT), Anthropic (Claude), Google Gemini, and DeepSeek.
 *
 * OpenAI and DeepSeek use the same /v1/chat/completions format.
 * Anthropic uses /v1/messages with different headers.
 * Gemini uses its own REST endpoint with a query-param API key.
 */
public class CloudTriageStrategy implements TriageStrategy {

    private static final Logger log = LoggerFactory.getLogger(CloudTriageStrategy.class);
    private static final int    TIMEOUT_SECONDS = 120;
    private static final int    MAX_RETRIES     = 2;

    private final LlmProvider       provider;
    private final String            apiKey;
    private final String            model;
    private final PromptBuilder     promptBuilder;
    private final OllamaResponseParser responseParser;
    private final ObjectMapper      mapper = new ObjectMapper();
    private final HttpClient        http   = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build();

    public CloudTriageStrategy(LlmProvider provider, String apiKey, String model) {
        this.provider       = provider;
        this.apiKey         = apiKey;
        this.model          = model;
        this.promptBuilder  = new PromptBuilder();
        this.responseParser = new OllamaResponseParser();
    }

    @Override
    public TriageResult triage(Finding finding) {
        String prompt = promptBuilder.build(finding);
        return runWithRetry(finding, prompt, PromptBuilder.PROMPT_VERSION);
    }

    @Override
    public TriageResult triageWithTemplate(Finding finding, String templateStr,
                                           String promptVersion, String sourceContext) {
        String prompt = promptBuilder.buildFromTemplate(finding, templateStr, sourceContext);
        return runWithRetry(finding, prompt, promptVersion);
    }

    @Override
    public String getModelName() { return provider.getDisplayName() + " / " + model; }

    @Override
    public boolean isAvailable() {
        // Lightweight connectivity check per provider
        try {
            return switch (provider) {
                case OPENAI    -> pingOpenAI();
                case DEEPSEEK  -> pingDeepSeek();
                case ANTHROPIC -> pingAnthropic();
                case GEMINI    -> pingGemini();
                default        -> false;
            };
        } catch (Exception e) {
            log.warn("Availability check failed for {}: {}", provider, e.getMessage());
            return false;
        }
    }

    // ── Core request dispatch ───────────────────────────────────────────────

    private String callProvider(String prompt) throws Exception {
        return switch (provider) {
            case OPENAI   -> callOpenAICompatible("https://api.openai.com/v1/chat/completions", prompt);
            case DEEPSEEK -> callOpenAICompatible("https://api.deepseek.com/v1/chat/completions", prompt);
            case ANTHROPIC -> callAnthropic(prompt);
            case GEMINI    -> callGemini(prompt);
            default        -> throw new IllegalStateException("Not a cloud provider: " + provider);
        };
    }

    // ── OpenAI-compatible (OpenAI + DeepSeek) ──────────────────────────────

    private String callOpenAICompatible(String endpoint, String prompt) throws Exception {
        String body = """
            {"model":"%s","messages":[{"role":"user","content":%s}],"temperature":0.1}
            """.formatted(model, mapper.writeValueAsString(prompt)).trim();

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new TriageException(provider + " API error " + resp.statusCode() + ": " + resp.body());
        }

        JsonNode root = mapper.readTree(resp.body());
        return root.path("choices").path(0).path("message").path("content").asText();
    }

    // ── Anthropic ───────────────────────────────────────────────────────────

    private String callAnthropic(String prompt) throws Exception {
        String body = """
            {"model":"%s","max_tokens":1024,"messages":[{"role":"user","content":%s}]}
            """.formatted(model, mapper.writeValueAsString(prompt)).trim();

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create("https://api.anthropic.com/v1/messages"))
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new TriageException("Anthropic API error " + resp.statusCode() + ": " + resp.body());
        }

        JsonNode root = mapper.readTree(resp.body());
        return root.path("content").path(0).path("text").asText();
    }

    // ── Google Gemini ───────────────────────────────────────────────────────

    private String callGemini(String prompt) throws Exception {
        String body = """
            {"contents":[{"parts":[{"text":%s}]}],"generationConfig":{"temperature":0.1,"maxOutputTokens":1024}}
            """.formatted(mapper.writeValueAsString(prompt)).trim();

        String url = "https://generativelanguage.googleapis.com/v1beta/models/"
            + model + ":generateContent?key=" + apiKey;

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new TriageException("Gemini API error " + resp.statusCode() + ": " + resp.body());
        }

        JsonNode root = mapper.readTree(resp.body());
        return root.path("candidates").path(0)
                   .path("content").path("parts").path(0).path("text").asText();
    }

    // ── Ping / availability checks ──────────────────────────────────────────

    private boolean pingOpenAI() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create("https://api.openai.com/v1/models"))
            .header("Authorization", "Bearer " + apiKey)
            .GET().timeout(Duration.ofSeconds(10)).build();
        return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
    }

    private boolean pingDeepSeek() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create("https://api.deepseek.com/v1/models"))
            .header("Authorization", "Bearer " + apiKey)
            .GET().timeout(Duration.ofSeconds(10)).build();
        return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
    }

    private boolean pingAnthropic() throws Exception {
        // Anthropic has no public /models list without billing — send a tiny message
        String body = """
            {"model":"%s","max_tokens":1,"messages":[{"role":"user","content":"ping"}]}
            """.formatted(model).trim();
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create("https://api.anthropic.com/v1/messages"))
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(15)).build();
        int code = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
        return code == 200 || code == 400; // 400 = valid key, bad request
    }

    private boolean pingGemini() throws Exception {
        String url = "https://generativelanguage.googleapis.com/v1beta/models?key=" + apiKey;
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET().timeout(Duration.ofSeconds(10)).build();
        return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
    }

    // ── Retry loop ──────────────────────────────────────────────────────────

    private TriageResult runWithRetry(Finding finding, String prompt, String promptVersion) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                String llmText = callProvider(prompt);
                TriageResult result = responseParser.parse(llmText, promptVersion);
                log.debug("Cloud triage success (attempt {}) provider={} finding={} verdict={}",
                    attempt, provider, finding.getId(), result.getVerdict());
                return result;
            } catch (TriageException e) {
                if (attempt == MAX_RETRIES) throw e;
                log.warn("Cloud triage attempt {} failed, retrying: {}", attempt, e.getMessage());
                prompt = prompt + "\n\nIMPORTANT: Return ONLY a valid JSON object. Start with { end with }.";
            } catch (Exception e) {
                throw new TriageException("Cloud LLM call failed: " + e.getMessage(), e);
            }
        }
        throw new TriageException("Cloud triage failed unexpectedly");
    }
}
