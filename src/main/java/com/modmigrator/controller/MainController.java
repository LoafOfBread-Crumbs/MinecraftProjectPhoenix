package com.modmigrator.controller;

import com.modmigrator.model.*;
import com.modmigrator.service.*;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.ResourceBundle;

public class MainController implements Initializable {

    // Drop zone
    @FXML private StackPane dropZone;
    @FXML private Label dropLabel;
    @FXML private Label dropSubLabel;

    // Mod info panel
    @FXML private VBox modInfoPanel;
    @FXML private Label modNameLabel;
    @FXML private Label modIdLabel;
    @FXML private Label modLoaderLabel;
    @FXML private Label modVersionLabel;
    @FXML private Label detectedMcVersionLabel;
    @FXML private Label modDescLabel;

    // Config panel
    @FXML private ComboBox<String> targetVersionCombo;
    @FXML private TextField outputDirField;
    @FXML private CheckBox decompileCheck;
    @FXML private CheckBox reportCheck;
    @FXML private CheckBox autoFixCheck;
    @FXML private Button migrateButton;
    @FXML private Button browseOutputButton;
    @FXML private Button browseJarButton;

    // Progress
    @FXML private VBox progressPanel;
    @FXML private ProgressBar progressBar;
    @FXML private Label progressLabel;
    @FXML private TextArea logArea;

    // Results
    @FXML private VBox resultsPanel;
    @FXML private Label resultStatusLabel;
    @FXML private Label resultSummaryLabel;
    @FXML private Label resultErrorsLabel;
    @FXML private Label resultWarningsLabel;
    @FXML private Label resultFixedLabel;
    @FXML private TableView<MigrationIssue> issuesTable;
    @FXML private TableColumn<MigrationIssue, String> colSeverity;
    @FXML private TableColumn<MigrationIssue, String> colCategory;
    @FXML private TableColumn<MigrationIssue, String> colTitle;
    @FXML private TableColumn<MigrationIssue, String> colLocation;
    @FXML private Button openReportButton;
    @FXML private Button openOutputButton;
    @FXML private Button openSourceButton;
    @FXML private Button resetButton;

    private ModInfo currentModInfo;
    private MigrationResult lastResult;

    private static final Path CACHE_DIR = Paths.get(
        System.getProperty("user.home"), ".modmigrator", "cache"
    );

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        setupDropZone();
        setupIssuesTable();
        loadVersions();
        setupDefaults();
    }

    private void setupDefaults() {
        outputDirField.setText(
            Paths.get(System.getProperty("user.home"), "Desktop", "ModMigrator-Output").toString()
        );
        decompileCheck.setSelected(true);
        reportCheck.setSelected(true);
        autoFixCheck.setSelected(true);
        modInfoPanel.setVisible(false);
        modInfoPanel.setManaged(false);
        progressPanel.setVisible(false);
        progressPanel.setManaged(false);
        resultsPanel.setVisible(false);
        resultsPanel.setManaged(false);
        migrateButton.setDisable(true);
    }

    private void setupDropZone() {
        dropZone.setOnDragOver(event -> {
            if (event.getGestureSource() != dropZone && event.getDragboard().hasFiles()) {
                boolean hasJar = event.getDragboard().getFiles().stream()
                    .anyMatch(f -> f.getName().endsWith(".jar"));
                if (hasJar) event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });

        dropZone.setOnDragEntered(event -> {
            if (event.getDragboard().hasFiles()) {
                dropZone.getStyleClass().add("drop-zone-active");
            }
            event.consume();
        });

        dropZone.setOnDragExited(event -> {
            dropZone.getStyleClass().remove("drop-zone-active");
            event.consume();
        });

        dropZone.setOnDragDropped(event -> {
            Dragboard db = event.getDragboard();
            boolean success = false;
            if (db.hasFiles()) {
                List<File> files = db.getFiles();
                File jarFile = files.stream()
                    .filter(f -> f.getName().endsWith(".jar"))
                    .findFirst().orElse(null);
                if (jarFile != null) {
                    loadJarFile(jarFile.toPath());
                    success = true;
                }
            }
            event.setDropCompleted(success);
            event.consume();
        });

        dropZone.setOnMouseClicked(event -> {
            if (event.getClickCount() == 1) {
                browseForJar();
            }
        });
    }

    private void setupIssuesTable() {
        colSeverity.setCellValueFactory(data ->
            new javafx.beans.property.SimpleStringProperty(
                data.getValue().isAutoFixed() ? "FIXED" : data.getValue().getSeverity().toString()
            )
        );
        colCategory.setCellValueFactory(data ->
            new javafx.beans.property.SimpleStringProperty(
                data.getValue().getCategory().toString().replace("_", " ")
            )
        );
        colTitle.setCellValueFactory(data ->
            new javafx.beans.property.SimpleStringProperty(data.getValue().getTitle())
        );
        colLocation.setCellValueFactory(data ->
            new javafx.beans.property.SimpleStringProperty(data.getValue().getLocation())
        );

        colSeverity.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item);
                    setStyle(switch (item) {
                        case "ERROR" -> "-fx-text-fill: #fca5a5; -fx-font-weight: bold;";
                        case "WARNING" -> "-fx-text-fill: #fcd34d; -fx-font-weight: bold;";
                        case "FIXED" -> "-fx-text-fill: #86efac; -fx-font-weight: bold;";
                        default -> "-fx-text-fill: #7dd3fc;";
                    });
                }
            }
        });

        issuesTable.getSelectionModel().selectedItemProperty().addListener((obs, old, newVal) -> {
            if (newVal != null) {
                showIssueDetail(newVal);
            }
        });
    }

    private void showIssueDetail(MigrationIssue issue) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Issue Detail");
        alert.setHeaderText(issue.getTitle());

        TextArea area = new TextArea(
            "Severity: " + issue.getSeverity() + "\n" +
            "Category: " + issue.getCategory() + "\n" +
            "Location: " + issue.getLocation() + "\n\n" +
            "Detail:\n" + issue.getDetail() + "\n\n" +
            (issue.getSuggestion() != null ? "Suggestion:\n" + issue.getSuggestion() : "")
        );
        area.setEditable(false);
        area.setWrapText(true);
        area.setPrefWidth(600);
        area.setPrefHeight(200);

        alert.getDialogPane().setContent(area);
        alert.showAndWait();
    }

    private void loadVersions() {
        targetVersionCombo.setItems(FXCollections.observableArrayList(
            "26.3", "26.2", "26.1.2", "26.1.1", "26.1",
            "1.21.11", "1.21.10", "1.21.9", "1.21.8", "1.21.7", "1.21.6",
            "1.21.5", "1.21.4", "1.21.3", "1.21.2", "1.21.1", "1.21",
            "1.20.6", "1.20.5", "1.20.4", "1.20.3", "1.20.2", "1.20.1", "1.20",
            "1.19.4", "1.19.3", "1.19.2", "1.19.1", "1.19",
            "1.18.2", "1.18.1", "1.18",
            "1.17.1", "1.17",
            "1.16.5", "1.16.4", "1.16.3", "1.16.2", "1.16.1", "1.16"
        ));
        targetVersionCombo.setValue("26.2");

        Task<List<String>> fetchTask = new Task<>() {
            @Override
            protected List<String> call() throws Exception {
                MappingFetcher fetcher = new MappingFetcher(CACHE_DIR);
                return fetcher.fetchAvailableMinecraftVersions();
            }
        };
        fetchTask.setOnSucceeded(e -> {
            List<String> versions = fetchTask.getValue();
            if (versions != null && !versions.isEmpty()) {
                String current = targetVersionCombo.getValue();
                targetVersionCombo.setItems(FXCollections.observableArrayList(versions));
                if (current != null && versions.contains(current)) {
                    targetVersionCombo.setValue(current);
                } else if (!versions.isEmpty()) {
                    targetVersionCombo.setValue(versions.get(0));
                }
            }
        });
        Thread versionThread = new Thread(fetchTask, "version-fetcher");
        versionThread.setDaemon(true);
        versionThread.start();
    }

    private void loadJarFile(Path jarPath) {
        log("Loading: " + jarPath.getFileName());

        Task<ModInfo> task = new Task<>() {
            @Override
            protected ModInfo call() throws Exception {
                JarInspector inspector = new JarInspector();
                return inspector.inspect(jarPath);
            }
        };

        task.setOnSucceeded(e -> {
            currentModInfo = task.getValue();
            displayModInfo(currentModInfo);
            migrateButton.setDisable(false);
            log("Mod loaded: " + currentModInfo);
        });

        task.setOnFailed(e -> {
            log("Error loading JAR: " + task.getException().getMessage());
            showError("Failed to inspect JAR", task.getException().getMessage());
        });

        Thread inspectorThread = new Thread(task, "jar-inspector");
        inspectorThread.setDaemon(true);
        inspectorThread.start();
    }

    private void displayModInfo(ModInfo info) {
        modNameLabel.setText(info.getModName() != null ? info.getModName() : info.getFileName());
        modIdLabel.setText(info.getModId() != null ? info.getModId() : "Unknown");
        modLoaderLabel.setText(info.getModLoader().toString());
        modVersionLabel.setText(info.getModVersion() != null ? info.getModVersion() : "Unknown");
        detectedMcVersionLabel.setText(
            info.getDetectedMinecraftVersion() != null ? info.getDetectedMinecraftVersion() : "Not detected"
        );
        modDescLabel.setText(info.getDescription() != null ? info.getDescription() : "");

        String loaderStyle = switch (info.getModLoader()) {
            case FORGE -> "-fx-text-fill: #f97316;";
            case NEOFORGE -> "-fx-text-fill: #a78bfa;";
            case FABRIC -> "-fx-text-fill: #60a5fa;";
            case QUILT -> "-fx-text-fill: #f472b6;";
            case UNKNOWN -> "-fx-text-fill: #94a3b8;";
        };
        modLoaderLabel.setStyle(loaderStyle + " -fx-font-weight: bold;");

        if (info.getDetectedMinecraftVersion() != null) {
            String detected = info.getDetectedMinecraftVersion();
            String current = targetVersionCombo.getValue();
            if (current == null || current.equals(detected)) {
                targetVersionCombo.setValue("1.21.1");
            }
        }

        modInfoPanel.setVisible(true);
        modInfoPanel.setManaged(true);
        dropLabel.setText(info.getFileName());
        dropSubLabel.setText("Click or drag to load a different JAR");
    }

    @FXML
    private void onBrowseJar() {
        browseForJar();
    }

    private void browseForJar() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select Mod JAR");
        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("JAR Files", "*.jar")
        );
        File file = chooser.showOpenDialog(dropZone.getScene().getWindow());
        if (file != null) {
            loadJarFile(file.toPath());
        }
    }

    @FXML
    private void onBrowseOutput() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select Output Directory");
        File current = new File(outputDirField.getText());
        if (current.exists()) chooser.setInitialDirectory(current);
        File dir = chooser.showDialog(outputDirField.getScene().getWindow());
        if (dir != null) {
            outputDirField.setText(dir.getAbsolutePath());
        }
    }

    @FXML
    private void onMigrate() {
        if (currentModInfo == null) {
            showError("No mod loaded", "Please drop a JAR file first.");
            return;
        }

        String targetVersion = targetVersionCombo.getValue();
        if (targetVersion == null || targetVersion.isBlank()) {
            showError("No target version", "Please select a target Minecraft version.");
            return;
        }

        String sourceVersion = currentModInfo.getDetectedMinecraftVersion();
        if (sourceVersion != null && sourceVersion.equals(targetVersion)) {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("Same Version");
            alert.setHeaderText("Source and target version are the same");
            alert.setContentText("The detected source version matches the target (" + targetVersion + "). Continue anyway?");
            if (alert.showAndWait().map(r -> r != ButtonType.OK).orElse(true)) return;
        }

        MigrationConfig config = new MigrationConfig();
        config.setSourceModInfo(currentModInfo);
        config.setTargetMinecraftVersion(targetVersion);
        config.setOutputDirectory(Paths.get(outputDirField.getText()));
        config.setDecompileSource(decompileCheck.isSelected());
        config.setGenerateReport(reportCheck.isSelected());
        config.setAttemptAutoFix(autoFixCheck.isSelected());

        runMigration(config);
    }

    private void runMigration(MigrationConfig config) {
        showProgress(true);
        logArea.clear();
        migrateButton.setDisable(true);
        resetButton.setDisable(true);
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);

        Task<MigrationResult> task = new Task<>() {
            @Override
            protected MigrationResult call() throws Exception {
                MigrationPipeline pipeline = new MigrationPipeline(CACHE_DIR);
                return pipeline.run(config, message -> Platform.runLater(() -> {
                    log(message);
                    progressLabel.setText(message);
                }));
            }
        };

        task.setOnSucceeded(e -> {
            lastResult = task.getValue();
            progressBar.setProgress(1.0);
            progressLabel.setText("Migration complete!");
            displayResults(lastResult, config);
            migrateButton.setDisable(false);
            resetButton.setDisable(false);
        });

        task.setOnFailed(e -> {
            progressBar.setProgress(0);
            progressLabel.setText("Migration failed.");
            log("FATAL: " + task.getException().getMessage());
            showError("Migration Failed", task.getException().getMessage());
            migrateButton.setDisable(false);
            resetButton.setDisable(false);
        });

        Thread pipelineThread = new Thread(task, "migration-pipeline");
        pipelineThread.setDaemon(true);
        pipelineThread.start();
    }

    private void displayResults(MigrationResult result, MigrationConfig config) {
        String statusStyle = switch (result.getStatus()) {
            case SUCCESS -> "-fx-text-fill: #22c55e;";
            case PARTIAL_SUCCESS -> "-fx-text-fill: #f59e0b;";
            case NEEDS_MANUAL_WORK -> "-fx-text-fill: #ef4444;";
            case FAILED -> "-fx-text-fill: #7f1d1d;";
        };

        resultStatusLabel.setText(result.getStatus().toString().replace("_", " "));
        resultStatusLabel.setStyle(statusStyle + " -fx-font-size: 1.3em; -fx-font-weight: bold;");
        resultSummaryLabel.setText(result.getSummary());
        resultErrorsLabel.setText(String.valueOf(result.getErrorCount()));
        resultWarningsLabel.setText(String.valueOf(result.getWarningCount()));
        resultFixedLabel.setText(String.valueOf(result.getAutoFixedCount()));

        issuesTable.setItems(FXCollections.observableArrayList(result.getIssues()));

        openReportButton.setDisable(result.getReportPath() == null);
        openOutputButton.setDisable(result.getOutputJar() == null);
        openSourceButton.setDisable(result.getOutputSourceDir() == null);

        resultsPanel.setVisible(true);
        resultsPanel.setManaged(true);
    }

    @FXML
    private void onOpenReport() {
        if (lastResult != null && lastResult.getReportPath() != null) {
            openFile(lastResult.getReportPath().toFile());
        }
    }

    @FXML
    private void onOpenOutput() {
        if (lastResult != null && lastResult.getOutputJar() != null) {
            openFile(lastResult.getOutputJar().getParent().toFile());
        }
    }

    @FXML
    private void onOpenSource() {
        if (lastResult != null && lastResult.getOutputSourceDir() != null) {
            openFile(lastResult.getOutputSourceDir().toFile());
        }
    }

    @FXML
    private void onReset() {
        currentModInfo = null;
        lastResult = null;
        dropLabel.setText("Drop your mod .jar here");
        dropSubLabel.setText("or click to browse");
        modInfoPanel.setVisible(false);
        modInfoPanel.setManaged(false);
        progressPanel.setVisible(false);
        progressPanel.setManaged(false);
        resultsPanel.setVisible(false);
        resultsPanel.setManaged(false);
        logArea.clear();
        migrateButton.setDisable(true);
        progressBar.setProgress(0);
        progressLabel.setText("Ready");
    }

    private void showProgress(boolean visible) {
        progressPanel.setVisible(visible);
        progressPanel.setManaged(visible);
    }

    private void log(String message) {
        Platform.runLater(() -> {
            logArea.appendText(message + "\n");
        });
    }

    private void openFile(File file) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(file);
            }
        } catch (IOException e) {
            showError("Could not open file", e.getMessage());
        }
    }

    private void showError(String title, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(title);
            alert.setHeaderText(title);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }
}
