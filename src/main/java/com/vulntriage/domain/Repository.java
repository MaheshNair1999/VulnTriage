package com.vulntriage.domain;

import java.time.LocalDateTime;

/**
 * Represents a source code repository registered in the system.
 * This is the top-level entity — all scans and findings belong to a repository.
 */
public class Repository {

    private long          id;
    private String        name;
    private String        localPath;
    private String        url;           // optional remote URL for display
    private LocalDateTime createdAt;
    private LocalDateTime lastScanned;   // null if never scanned

    // ── Constructors ───────────────────────────────────────────────────────

    public Repository() {}

    public Repository(String name, String localPath, String url) {
        this.name      = name;
        this.localPath = localPath;
        this.url       = url;
        this.createdAt = LocalDateTime.now();
    }

    // ── Getters & Setters ──────────────────────────────────────────────────

    public long getId()                        { return id; }
    public void setId(long id)                 { this.id = id; }

    public String getName()                    { return name; }
    public void setName(String name)           { this.name = name; }

    public String getLocalPath()               { return localPath; }
    public void setLocalPath(String localPath) { this.localPath = localPath; }

    public String getUrl()                     { return url; }
    public void setUrl(String url)             { this.url = url; }

    public LocalDateTime getCreatedAt()                    { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt)      { this.createdAt = createdAt; }

    public LocalDateTime getLastScanned()                  { return lastScanned; }
    public void setLastScanned(LocalDateTime lastScanned)  { this.lastScanned = lastScanned; }

    @Override
    public String toString() {
        return "Repository{id=" + id + ", name='" + name + "', path='" + localPath + "'}";
    }
}
