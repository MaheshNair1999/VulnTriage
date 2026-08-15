package com.vulntriage.ui.prompts;

import com.vulntriage.app.AppContext;
import com.vulntriage.domain.PromptTemplate;
import javafx.application.Platform;
import java.util.function.BooleanSupplier;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.control.ButtonBar;
import javafx.scene.layout.*;

import java.util.List;

/**
 * Prompt Management screen.
 *
 * Left panel: list of prompt templates (name + version).
 * Right panel: edit form — name, version, description, and the template text.
 *
 * Built-in templates (v1.0, v2.0) can be edited but not deleted.
 * The template TextArea supports the {{placeholder}} syntax documented in PromptBuilder.
 */
public class PromptsView {

    private static final String BG    = "#F1F5F9";
    private static final String CARD  = "#FFFFFF";
    private static final String TEXT  = "#111827";
    private static final String MUTED = "#6B7280";
    private static final String BLUE  = "#1D4ED8";
    private static final String GREEN = "#059669";
    private static final String RED   = "#DC2626";

    private final AppContext ctx = AppContext.getInstance();

    private final ObservableList<PromptTemplate> templates = FXCollections.observableArrayList();
    private ListView<PromptTemplate>            listView;
    private TextField    nameField;
    private TextField    versionField;
    private TextArea     descArea;
    private TextArea     templateArea;
    private Label        statusLabel;
    private Button       deleteBtn;

    /** True when any form field has been edited since the last load or clear. */
    private boolean dirty           = false;
    /** Suppresses the selection listener's dirty-check while we restore a cancelled selection. */
    private boolean ignoreSelection = false;

    public Node build() {
        VBox root = new VBox(0);
        root.setStyle("-fx-background-color: " + BG + ";");
        root.getChildren().addAll(buildHeader(), buildBody());
        // Register with MainWindow's navigation guard so leaving the tab also prompts
        root.getProperties().put("navigationGuard", (BooleanSupplier) this::allowNavigateAway);
        loadTemplates();
        return root;
    }

    // ── Header ─────────────────────────────────────────────────────────────

    private HBox buildHeader() {
        HBox header = new HBox();
        header.setPadding(new Insets(18, 28, 14, 28));
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-background-color: " + CARD + "; "
            + "-fx-border-color: #E5E7EB; -fx-border-width: 0 0 1 0;");

        VBox titleBlock = new VBox(2);
        Label title = new Label("Prompt Templates");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: " + TEXT + ";");
        Label sub = new Label("Manage LLM prompt templates. Select a prompt in the Triage tab to use it.");
        sub.setStyle("-fx-font-size: 12px; -fx-text-fill: " + MUTED + ";");
        titleBlock.getChildren().addAll(title, sub);
        header.getChildren().add(titleBlock);
        return header;
    }

    // ── Body ───────────────────────────────────────────────────────────────

    private HBox buildBody() {
        HBox body = new HBox(0);
        VBox.setVgrow(body, Priority.ALWAYS);
        body.getChildren().addAll(buildListPanel(), buildEditPanel());
        return body;
    }

    // ── Left: list panel ───────────────────────────────────────────────────

    private VBox buildListPanel() {
        VBox panel = new VBox(12);
        panel.setPrefWidth(260);
        panel.setMinWidth(220);
        panel.setPadding(new Insets(20, 16, 20, 24));
        panel.setStyle("-fx-background-color: " + CARD + "; "
            + "-fx-border-color: #E5E7EB; -fx-border-width: 0 1 0 0;");

        Label heading = new Label("Templates");
        heading.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: " + TEXT + ";");

        listView = new ListView<>(templates);
        VBox.setVgrow(listView, Priority.ALWAYS);
        listView.setStyle("-fx-background-color: white;");
        listView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(PromptTemplate item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setGraphic(null); return; }
                VBox cell = new VBox(2);
                Label name = new Label(item.getName());
                name.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: " + TEXT + ";");
                Label ver = new Label(item.getVersion() + (item.isBuiltIn() ? "  ·  built-in" : ""));
                ver.setStyle("-fx-font-size: 10px; -fx-text-fill: " + MUTED + ";");
                cell.getChildren().addAll(name, ver);
                setGraphic(cell);
                setText(null);
            }
        });
        listView.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            if (ignoreSelection) return;
            if (dirty) {
                UnsavedChoice choice = askUnsaved();
                if (choice == UnsavedChoice.CANCEL) {
                    // Restore the previous selection without re-triggering this listener
                    ignoreSelection = true;
                    if (old != null) listView.getSelectionModel().select(old);
                    else             listView.getSelectionModel().clearSelection();
                    ignoreSelection = false;
                    return;
                }
                if (choice == UnsavedChoice.SAVE) {
                    // Save `old` (the template being edited), NOT `sel` (the one being navigated to)
                    final long navToId = sel != null ? sel.getId() : -1;
                    if (!saveSelected(old)) {
                        // Save failed — restore previous selection and stay put
                        ignoreSelection = true;
                        if (old != null) listView.getSelectionModel().select(old);
                        else             listView.getSelectionModel().clearSelection();
                        ignoreSelection = false;
                        return;
                    }
                    // Save succeeded; loadTemplates() was called inside saveSelected, so find the
                    // refreshed version of sel by ID and navigate to it
                    if (navToId != -1) {
                        templates.stream().filter(t -> t.getId() == navToId).findFirst()
                            .ifPresent(found -> {
                                ignoreSelection = true;
                                listView.getSelectionModel().select(found);
                                ignoreSelection = false;
                                populateForm(found);
                            });
                    }
                    return;
                }
                // DISCARD: fall through — form will be replaced by populateForm/clearForm below
            }
            if (sel != null) populateForm(sel);
        });

        Button addBtn = primaryBtn("+ New Prompt");
        addBtn.setMaxWidth(Double.MAX_VALUE);
        addBtn.setOnAction(e -> {
            if (dirty) {
                UnsavedChoice choice = askUnsaved();
                if (choice == UnsavedChoice.CANCEL) return;
                if (choice == UnsavedChoice.SAVE && !saveSelected()) return;
            }
            createNew();
        });

        deleteBtn = new Button("Delete");
        deleteBtn.setMaxWidth(Double.MAX_VALUE);
        deleteBtn.setDisable(true);
        deleteBtn.setStyle("-fx-background-color: #FEF2F2; -fx-text-fill: " + RED + "; "
            + "-fx-background-radius: 6; -fx-font-size: 12px; "
            + "-fx-border-color: #FECACA; -fx-border-radius: 6;");
        deleteBtn.setOnAction(e -> deleteSelected());

        panel.getChildren().addAll(heading, listView, addBtn, deleteBtn);
        return panel;
    }

    // ── Right: edit panel ──────────────────────────────────────────────────

    private VBox buildEditPanel() {
        VBox panel = new VBox(12);
        panel.setPadding(new Insets(24, 28, 24, 20));
        HBox.setHgrow(panel, Priority.ALWAYS);
        panel.setStyle("-fx-background-color: " + BG + ";");

        Label heading = new Label("Edit Template");
        heading.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: " + TEXT + ";");

        // Name + version row
        HBox topRow = new HBox(12);
        VBox nameBox = new VBox(4);
        nameBox.getChildren().addAll(fieldLabel("Name"), (nameField = styledField("e.g. Context-Enriched Prompt")));
        HBox.setHgrow(nameBox, Priority.ALWAYS);

        VBox verBox = new VBox(4);
        verBox.setPrefWidth(100);
        verBox.getChildren().addAll(fieldLabel("Version"), (versionField = styledField("e.g. v3.0")));

        topRow.getChildren().addAll(nameBox, verBox);

        // Description
        Label descLbl = fieldLabel("Description");
        descArea = new TextArea();
        descArea.setPromptText("Brief description of what this prompt does…");
        descArea.setPrefRowCount(2);
        descArea.setWrapText(true);
        styleArea(descArea);

        // Placeholder hint
        Label hintLbl = new Label(
            "Placeholders:  {{rule_id}}  {{severity}}  {{category}}  {{message}}  "
            + "{{code_snippet}}  {{line_number}}  {{file_path}}  {{source_context}}");
        hintLbl.setStyle("-fx-font-size: 10px; -fx-text-fill: #7C3AED; "
            + "-fx-background-color: #F5F3FF; -fx-padding: 6 10; -fx-background-radius: 4;");
        hintLbl.setWrapText(true);

        // Template text
        Label templateLbl = fieldLabel("Prompt Template");
        templateArea = new TextArea();
        templateArea.setPromptText("Paste or type the prompt template here…");
        templateArea.setWrapText(true);
        templateArea.setStyle("-fx-font-family: 'Courier New'; -fx-font-size: 11px;");
        VBox.setVgrow(templateArea, Priority.ALWAYS);
        styleArea(templateArea);

        // Action row
        statusLabel = new Label("");
        statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: " + MUTED + ";");
        statusLabel.setWrapText(true);

        Button saveBtn = primaryBtn("Save");
        saveBtn.setPrefWidth(110);
        saveBtn.setOnAction(e -> saveSelected());

        HBox actionRow = new HBox(12, saveBtn, statusLabel);
        actionRow.setAlignment(Pos.CENTER_LEFT);

        // Mark dirty whenever the user types in any field
        nameField.textProperty()   .addListener((o, a, b) -> dirty = true);
        versionField.textProperty().addListener((o, a, b) -> dirty = true);
        descArea.textProperty()    .addListener((o, a, b) -> dirty = true);
        templateArea.textProperty().addListener((o, a, b) -> dirty = true);

        panel.getChildren().addAll(
            heading, topRow,
            descLbl, descArea,
            hintLbl,
            templateLbl, templateArea,
            actionRow
        );
        return panel;
    }

    // ── Data operations ────────────────────────────────────────────────────

    private void loadTemplates() {
        List<PromptTemplate> list = ctx.promptTemplateRepo().findAll();
        templates.setAll(list);
        if (!list.isEmpty()) listView.getSelectionModel().selectFirst();
    }

    private void populateForm(PromptTemplate t) {
        nameField.setText(t.getName() != null ? t.getName() : "");
        versionField.setText(t.getVersion() != null ? t.getVersion() : "");
        descArea.setText(t.getDescription() != null ? t.getDescription() : "");
        templateArea.setText(t.getTemplate() != null ? t.getTemplate() : "");
        deleteBtn.setDisable(t.isBuiltIn());
        statusLabel.setText("");
        dirty = false;  // reset after programmatic load
    }

    /** Saves current form to whatever is currently selected in the list (or new template). */
    private boolean saveSelected() {
        return saveSelected(listView.getSelectionModel().getSelectedItem());
    }

    /** Saves the current form to the given target template (null = new template). */
    private boolean saveSelected(PromptTemplate target) {
        PromptTemplate selected = target;
        String name    = nameField.getText().trim();
        String version = versionField.getText().trim();
        String desc    = descArea.getText().trim();
        String text    = templateArea.getText().trim();

        if (name.isBlank() || version.isBlank()) {
            setStatus("Name and Version are required.", RED);
            return false;
        }
        if (text.isBlank()) {
            setStatus("Template text cannot be empty.", RED);
            return false;
        }

        if (selected == null) {
            // New template
            PromptTemplate t = new PromptTemplate(name, version, desc, text, false);
            try {
                ctx.promptTemplateRepo().save(t);
                dirty = false;
                loadTemplates();
                templates.stream().filter(p -> version.equals(p.getVersion()))
                    .findFirst().ifPresent(p -> listView.getSelectionModel().select(p));
                setStatus("Saved.", GREEN);
                return true;
            } catch (Exception ex) {
                setStatus("Error: " + ex.getMessage(), RED);
                return false;
            }
        } else {
            selected.setName(name);
            selected.setVersion(version);
            selected.setDescription(desc);
            selected.setTemplate(text);
            try {
                ctx.promptTemplateRepo().update(selected);
                dirty = false;
                loadTemplates();
                setStatus("Saved.", GREEN);
                return true;
            } catch (Exception ex) {
                setStatus("Error: " + ex.getMessage(), RED);
                return false;
            }
        }
    }

    private void createNew() {
        ignoreSelection = true;
        listView.getSelectionModel().clearSelection();
        ignoreSelection = false;
        nameField.clear();
        versionField.clear();
        descArea.clear();
        templateArea.clear();
        deleteBtn.setDisable(true);
        dirty = false;  // clearing the form is not an edit
        setStatus("Fill in the fields and click Save.", MUTED);
        nameField.requestFocus();
    }

    private void deleteSelected() {
        PromptTemplate t = listView.getSelectionModel().getSelectedItem();
        if (t == null || t.isBuiltIn()) return;

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
            "Delete prompt \"" + t.getName() + "\" (" + t.getVersion() + ")?",
            ButtonType.YES, ButtonType.NO);
        confirm.setTitle("Delete Prompt Template");
        confirm.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.YES) {
                ctx.promptTemplateRepo().deleteById(t.getId());
                loadTemplates();
                setStatus("Deleted.", MUTED);
            }
        });
    }

    private void setStatus(String msg, String color) {
        statusLabel.setText(msg);
        statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: " + color + ";");
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private Label fieldLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #374151;");
        return l;
    }

    private TextField styledField(String prompt) {
        TextField f = new TextField();
        f.setPromptText(prompt);
        f.setStyle("-fx-font-size: 12px; -fx-border-color: #E5E7EB; "
            + "-fx-border-radius: 5; -fx-background-radius: 5; -fx-padding: 5 8;");
        return f;
    }

    private void styleArea(TextArea a) {
        a.setStyle(a.getStyle()
            + "-fx-border-color: #E5E7EB; -fx-border-radius: 5; "
            + "-fx-background-radius: 5; -fx-padding: 4;");
    }

    private Button primaryBtn(String text) {
        Button b = new Button(text);
        b.setStyle("-fx-background-color: " + BLUE + "; -fx-text-fill: white; "
            + "-fx-background-radius: 6; -fx-font-size: 12px; -fx-font-weight: bold; "
            + "-fx-padding: 7 14;");
        return b;
    }

    // ── Unsaved-changes prompt ─────────────────────────────────────────────

    /** Called by MainWindow before navigating away. Returns false to cancel navigation. */
    private boolean allowNavigateAway() {
        if (!dirty) return true;
        UnsavedChoice choice = askUnsaved();
        if (choice == UnsavedChoice.CANCEL) return false;
        if (choice == UnsavedChoice.SAVE) {
            if (!saveSelected()) return false;  // save failed — stay on Prompts so user can fix it
        } else {
            // Discard: revert the form so it's clean if the user comes back
            PromptTemplate sel = listView.getSelectionModel().getSelectedItem();
            if (sel != null) {
                populateForm(sel);  // restores saved values and resets dirty
            } else {
                // Was in new-template mode — clear the unsaved content
                ignoreSelection = true;
                listView.getSelectionModel().clearSelection();
                ignoreSelection = false;
                nameField.clear(); versionField.clear(); descArea.clear(); templateArea.clear();
                dirty = false;
            }
        }
        return true;
    }

    private enum UnsavedChoice { SAVE, DISCARD, CANCEL }

    private UnsavedChoice askUnsaved() {
        ButtonType save    = new ButtonType("Save",    ButtonBar.ButtonData.YES);
        ButtonType discard = new ButtonType("Discard", ButtonBar.ButtonData.NO);
        ButtonType cancel  = new ButtonType("Cancel",  ButtonBar.ButtonData.CANCEL_CLOSE);

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Unsaved Changes");
        alert.setHeaderText("You have unsaved changes.");
        alert.setContentText("Do you want to save or discard your changes?");
        alert.getButtonTypes().setAll(save, discard, cancel);

        return alert.showAndWait()
            .map(btn -> {
                if (btn == save)    return UnsavedChoice.SAVE;
                if (btn == discard) return UnsavedChoice.DISCARD;
                return UnsavedChoice.CANCEL;
            })
            .orElse(UnsavedChoice.CANCEL);
    }
}
