package com.vulntriage.ui;

import com.vulntriage.config.ThemeColors;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import static com.vulntriage.config.ThemeColors.*;

/**
 * Sidebar navigation button with hover and active states.
 */
public class NavButton extends HBox {

    private static final String NORMAL = "-fx-background-color: transparent; -fx-background-radius: 6px;";

    private final String    label;
    private final Label     iconLabel;
    private final Label     textLabel;
    private boolean         active = false;

    public NavButton(String icon, String label, Runnable onAction) {
        this.label = label;

        setSpacing(10);
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(9, 14, 9, 14));
        setStyle(NORMAL);
        setCursor(Cursor.HAND);

        iconLabel = new Label(icon);
        iconLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: " + ThemeColors.MUTED + ";");
        iconLabel.setMinWidth(18);

        textLabel = new Label(label);
        textLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #9CA3AF;");

        getChildren().addAll(iconLabel, textLabel);

        setOnMouseEntered(e -> { if (!active) setStyle("-fx-background-color: " + ThemeColors.SIDEBAR_HOVER + "; -fx-background-radius: 6px;"); });
        setOnMouseExited (e -> { if (!active) setStyle(NORMAL); });
        setOnMouseClicked(e -> onAction.run());
    }

    public String getLabel() { return label; }

    public void setActive() {
        active = true;
        setStyle("-fx-background-color: " + ThemeColors.BLUE + "; -fx-background-radius: 6px;");
        iconLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: white;");
        textLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: white; -fx-font-weight: bold;");
    }

    public void setInactive() {
        active = false;
        setStyle(NORMAL);
        iconLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: " + ThemeColors.MUTED + ";");
        textLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #9CA3AF;");
    }
}