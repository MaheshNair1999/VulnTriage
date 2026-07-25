package com.vulntriage.triage.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vulntriage.triage.api.TriageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Thin HTTP client for the Ollama REST API.
 *
 * Ollama exposes a local server (default: http://localhost:11434).
 * We call the /api/generate endpoint with a prompt and get a response.
 *
 * Kept separate from OllamaTriageStrategy so it can be tested independently
 * and swapped with a mock in tests.
 *
 * Ollama API reference: https://github.com/ollama/ollama/blob/main/docs/api.md
 */
public class OllamaClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaClient.class);

    private final String     baseUrl;
    private final String     model;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public OllamaClient(String baseUrl, String model) {
        this.baseUrl = baseUrl;
        this.model   = model;
        this.mapper  = new ObjectMapper();
        this.http    = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    /**
     * Send a prompt to Ollama and return the raw response text.
     *
     * @param prompt the full prompt string
     * @param timeoutSeconds max time to wait for response
     * @return the model's response text
     * @throws TriageException if the HTTP call fails or times out
     */
    public String generate(String prompt, int timeoutSeconds) {
        try {
            // Build request body
            ObjectNode body = mapper.createObjectNode();
            body.put("model",      model);
            body.put("prompt",     prompt);
            body.put("stream",     false);   // get full response at once, not streamed tokens
            body.put("keep_alive", 0);       // unload model from VRAM immediately after response
                                             // prevents VRAM accumulation across findings

            // Cap context window to reduce VRAM per request
            // Default is 4096+ tokens which keeps too much on GPU across calls
            ObjectNode options = mapper.createObjectNode();
            options.put("num_ctx", 2048);
            body.set("options", options);

            String requestBody = mapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/generate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build();

            log.debug("Sending prompt to Ollama (model={}), length={} chars",
                model, prompt.length());

            HttpResponse<String> response = http.send(
                request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new TriageException(
                    "Ollama returned HTTP " + response.statusCode()
                    + ": " + response.body());
            }

            // Ollama wraps the response in { "response": "...", "done": true, ... }
            JsonNode root = mapper.readTree(response.body());
            String text = root.path("response").asText("");

            if (text.isBlank()) {
                throw new TriageException(
                    "Ollama returned an empty response for model " + model);
            }

            log.debug("Ollama response received: {} chars", text.length());
            return text;

        } catch (TriageException e) {
            throw e;
        } catch (java.net.http.HttpTimeoutException e) {
            throw new TriageException(
                "Ollama request timed out after " + timeoutSeconds + "s. "
                + "Try increasing the timeout or using a smaller model.", e);
        } catch (Exception e) {
            throw new TriageException(
                "Failed to communicate with Ollama at " + baseUrl + ": " + e.getMessage(), e);
        }
    }

    /**
     * Check whether Ollama is running and reachable.
     * Calls GET /api/tags (lists available models) — lightweight health check.
     */
    public boolean isReachable() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/tags"))
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();

            HttpResponse<String> response = http.send(
                request, HttpResponse.BodyHandlers.ofString());

            return response.statusCode() == 200;

        } catch (Exception e) {
            log.debug("Ollama not reachable at {}: {}", baseUrl, e.getMessage());
            return false;
        }
    }

    /**
     * Check whether the configured model is available in Ollama.
     * If it's not pulled yet, isAvailable() will return false.
     */
    public boolean isModelAvailable() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/tags"))
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();

            HttpResponse<String> response = http.send(
                request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) return false;

            JsonNode root   = mapper.readTree(response.body());
            JsonNode models = root.path("models");

            if (!models.isArray()) return false;

            for (JsonNode m : models) {
                String name = m.path("name").asText("");
                // Ollama model names can be "qwen3:8b" or "qwen3:8b-instruct" etc.
                if (name.startsWith(model) || model.startsWith(name.split(":")[0])) {
                    return true;
                }
            }
            return false;

        } catch (Exception e) {
            return false;
        }
    }

    public String getBaseUrl() { return baseUrl; }
    public String getModel()   { return model; }
}
