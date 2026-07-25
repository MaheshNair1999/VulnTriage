package com.vulntriage.event;

import javafx.application.Platform;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/**
 * Observer that bridges pipeline events to the JavaFX UI thread.
 *
 * The pipeline runs on a background thread. JavaFX controls can only
 * be updated on the FX application thread. Platform.runLater() queues
 * the update safely.
 *
 * UI controllers bind their progress bar and status label to the
 * progressProperty and statusProperty respectively.
 */
public class UiProgressUpdater implements PipelineObserver {

    private final IntegerProperty progressProperty = new SimpleIntegerProperty(0);
    private final StringProperty  statusProperty   = new SimpleStringProperty("");

    @Override
    public void onEvent(PipelineEvent event) {
        // Always dispatch to FX thread — safe to call from any thread
        Platform.runLater(() -> {
            statusProperty.set(event.getMessage());
            if (event.getProgress() >= 0) {
                progressProperty.set(event.getProgress());
            }
        });
    }

    /** Bind a ProgressBar's progressProperty to this. Values are 0-100. */
    public IntegerProperty progressProperty() { return progressProperty; }

    /** Bind a Label's textProperty to this for a status message. */
    public StringProperty  statusProperty()   { return statusProperty; }
}
