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
     * TextArea to #070B14. Inline style on the child node itself beats every
     * CSS cascade rule, which is the only reliable way to override Modena's
     * .text-area .content background inside a Dialog.
     */
    public static void fixCodeSnippetBackground(Dialog<?> dialog, TextArea... areas) {
        dialog.setOnShown(e -> Platform.runLater(() -> {
            for (TextArea area : areas) {
                javafx.scene.Node content = area.lookup(".content");
                if (content != null) {
                    content.setStyle("-fx-background-color: #070B14;");
                }
            }
        }));
    }
}
