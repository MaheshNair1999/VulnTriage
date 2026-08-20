package com.vulntriage.domain.enums;

public enum FinalVerdict {
    FIXED    ("Fixed",        "Vulnerability has been remediated"),
    WONT_FIX ("Won't Fix",   "Accepted risk — will not be remediated"),
    DEFERRED ("Defer",        "Acknowledged but not addressed yet");

    private final String displayName;
    private final String description;

    FinalVerdict(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }

    public static FinalVerdict fromName(String name) {
        for (FinalVerdict v : values()) {
            if (v.name().equalsIgnoreCase(name)) return v;
        }
        return null;
    }
}
