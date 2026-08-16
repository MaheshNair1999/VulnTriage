package com.vulntriage.config;

public final class ThemeColors {

    private ThemeColors() {}

    public static void apply(boolean dark) {
        BG         = dark ? "#0F172A" : "#F1F5F9";
        CARD       = dark ? "#1E293B" : "#FFFFFF";
        CARD_BG    = CARD;
        TEXT       = dark ? "#E2E8F0" : "#111827";
        MUTED      = dark ? "#94A3B8" : "#6B7280";
        DIM        = dark ? "#94A3B8" : "#374151";
        SURFACE    = dark ? "#1E293B" : "#F9FAFB";
        SURFACE2   = dark ? "#0F172A" : "#F8FAFC";
        NEUTRAL_BG = dark ? "#374151" : "#F3F4F6";
        BORDER     = dark ? "#334155" : "#E5E7EB";
        INPUT_BG   = dark ? "#1E293B" : "#FFFFFF";

        BLUE   = dark ? "#3B82F6" : "#1D4ED8";
        ACCENT = BLUE;
        GREEN  = dark ? "#34D399" : "#059669";
        RED    = dark ? "#F87171" : "#DC2626";
        AMBER  = dark ? "#FCD34D" : "#D97706";
        TEAL   = dark ? "#22D3EE" : "#0891B2";
        PURPLE = dark ? "#A78BFA" : "#7C3AED";

        BTN_BG_SECONDARY     = dark ? "#1E3A5F" : "#EFF6FF";
        BTN_BORDER_SECONDARY = dark ? "#3B82F6" : "#BFDBFE";
        BTN_FG_SECONDARY     = dark ? "#93C5FD" : "#1E40AF";

        SIDEBAR_BG    = dark ? "#020617" : "#111827";
        SIDEBAR_HOVER = dark ? "#1E293B" : "#1F2937";

        RED_LIGHT_BG = dark ? "#450A0A" : "#FEF2F2";
        RED_BG       = dark ? "#450A0A" : "#FEE2E2";
        RED_BORDER   = dark ? "#991B1B" : "#FECACA";
        RED_DIM      = dark ? "#FCA5A5" : "#991B1B";
        RED_EXTRA    = dark ? "#FCA5A5" : "#7F1D1D";

        AMBER_BG    = dark ? "#451A03" : "#FEF3C7";
        AMBER_DIM   = dark ? "#FDE68A" : "#92400E";
        AMBER_EXTRA = dark ? "#FDE68A" : "#B45309";

        GREEN_BG  = dark ? "#052E16" : "#D1FAE5";
        GREEN_DIM = dark ? "#6EE7B7" : "#065F46";

        BLUE_BG    = dark ? "#1E3A5F" : "#DBEAFE";
        BLUE_EXTRA = dark ? "#93C5FD" : "#1E3A8A";

        PURPLE_BG  = dark ? "#2E1065" : "#EDE9FE";
        PURPLE_DIM = dark ? "#C4B5FD" : "#6D28D9";

        TEAL_BG = dark ? "#042F2E" : "#ECFEFF";
    }

    // Core palette
    public static String BG;
    public static String CARD;
    public static String CARD_BG;
    public static String TEXT;
    public static String MUTED;
    public static String DIM;
    public static String SURFACE;
    public static String SURFACE2;
    public static String NEUTRAL_BG;
    public static String BORDER;
    public static String INPUT_BG;

    // Accent
    public static String BLUE;
    public static String ACCENT;
    public static String GREEN;
    public static String RED;
    public static String AMBER;
    public static String TEAL;
    public static String PURPLE;

    // Secondary button
    public static String BTN_BG_SECONDARY;
    public static String BTN_BORDER_SECONDARY;
    public static String BTN_FG_SECONDARY;

    // Sidebar
    public static String SIDEBAR_BG;
    public static String SIDEBAR_HOVER;

    // Badge colors
    public static String RED_LIGHT_BG;
    public static String RED_BG;
    public static String RED_BORDER;
    public static String RED_DIM;
    public static String RED_EXTRA;
    public static String AMBER_BG;
    public static String AMBER_DIM;
    public static String AMBER_EXTRA;
    public static String GREEN_BG;
    public static String GREEN_DIM;
    public static String BLUE_BG;
    public static String BLUE_EXTRA;
    public static String PURPLE_BG;
    public static String PURPLE_DIM;
    public static String TEAL_BG;

    static {
        apply(false);
    }
}
