package com.vulntriage.ui;

import com.vulntriage.app.AppContext;
import javafx.application.Platform;
import javafx.scene.control.Dialog;
import javafx.scene.control.TextArea;
import static com.vulntriage.config.ThemeColors.*;

public final class UIUtils {

    private UIUtils() {}

    public static void applyTheme(Dialog<?> dialog) {
        if (!AppContext.getInstance().isDarkMode()) return;
        try {
            String css = UIUtils.class.getResource("/dark-mode.css").toExternalForm();
            dialog.getDialogPane().getStylesheets().add(css);
        } catch (Exception ignored) {}
        dialog.getDialogPane().setStyle("-fx-background-color: " + BG + ";");
    }

    /**
     * After the dialog is shown, directly style the inner content node of each
     * TextArea so the background matches the current theme. Inline style on the
     * child node beats every CSS cascade rule — the only reliable way to override
     * Modena's .text-area .content background inside a Dialog.
     */
    public static void fixCodeSnippetBackground(Dialog<?> dialog, TextArea... areas) {
        boolean dark = AppContext.getInstance().isDarkMode();
        String bg   = dark ? "#070B14" : "#F1F5F9";
        String text = dark ? "#CDD6F4" : "#111827";
        dialog.setOnShown(e -> Platform.runLater(() -> {
            for (TextArea area : areas) {
                javafx.scene.Node content = area.lookup(".content");
                if (content != null) {
                    content.setStyle("-fx-background-color: " + bg + ";");
                }
                area.setStyle(area.getStyle() + " -fx-text-fill: " + text + ";");
            }
        }));
    }
}
