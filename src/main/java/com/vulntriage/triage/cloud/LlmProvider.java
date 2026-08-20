package com.vulntriage.triage.cloud;

public enum LlmProvider {

    OLLAMA   ("Ollama (Local)",         "qwen3:8b",                      false),
    OPENAI   ("OpenAI (GPT)",           "gpt-4o-mini",                   true),
    ANTHROPIC("Anthropic (Claude)",     "claude-3-5-haiku-20241022",     true),
    GEMINI   ("Google Gemini",          "gemini-1.5-flash",              true),
    DEEPSEEK ("DeepSeek",               "deepseek-chat",                 true);

    private final String displayName;
    private final String defaultModel;
    private final boolean requiresApiKey;

    LlmProvider(String displayName, String defaultModel, boolean requiresApiKey) {
        this.displayName    = displayName;
        this.defaultModel   = defaultModel;
        this.requiresApiKey = requiresApiKey;
    }

    public String getDisplayName()   { return displayName; }
    public String getDefaultModel()  { return defaultModel; }
    public boolean requiresApiKey()  { return requiresApiKey; }

    @Override
    public String toString() { return displayName; }

    public static LlmProvider fromKey(String key) {
        if (key == null) return OLLAMA;
        for (LlmProvider p : values()) {
            if (p.name().equalsIgnoreCase(key)) return p;
        }
        return OLLAMA;
    }
}
