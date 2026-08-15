package com.vulntriage.domain;

import java.time.LocalDateTime;

/**
 * A stored LLM prompt template.
 *
 * Templates use named double-brace placeholders that PromptBuilder replaces:
 *   {{rule_id}}, {{severity}}, {{category}}, {{message}},
 *   {{code_snippet}}, {{line_number}}, {{file_path}}, {{source_context}}
 *
 * Built-in templates (builtIn=true) ship pre-seeded with the application
 * and cannot be deleted from the UI, though their text can be edited.
 */
public class PromptTemplate {

    private long          id;
    private String        name;
    private String        version;
    private String        description;
    private String        template;
    private boolean       builtIn;
    private LocalDateTime createdAt;

    public PromptTemplate() {}

    public PromptTemplate(String name, String version, String description,
                          String template, boolean builtIn) {
        this.name        = name;
        this.version     = version;
        this.description = description;
        this.template    = template;
        this.builtIn     = builtIn;
        this.createdAt   = LocalDateTime.now();
    }

    public long          getId()          { return id; }
    public String        getName()        { return name; }
    public String        getVersion()     { return version; }
    public String        getDescription() { return description; }
    public String        getTemplate()    { return template; }
    public boolean       isBuiltIn()      { return builtIn; }
    public LocalDateTime getCreatedAt()   { return createdAt; }

    public void setId(long id)                    { this.id = id; }
    public void setName(String name)              { this.name = name; }
    public void setVersion(String version)        { this.version = version; }
    public void setDescription(String d)          { this.description = d; }
    public void setTemplate(String template)      { this.template = template; }
    public void setBuiltIn(boolean builtIn)       { this.builtIn = builtIn; }
    public void setCreatedAt(LocalDateTime t)     { this.createdAt = t; }

    @Override
    public String toString() { return name + " (" + version + ")"; }
}
