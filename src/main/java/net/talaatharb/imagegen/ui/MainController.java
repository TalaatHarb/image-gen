package net.talaatharb.imagegen.ui;

import java.io.IOException;
import java.util.List;

import com.google.inject.Inject;

import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Spinner;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.util.StringConverter;
import net.talaatharb.imagegen.config.AppSettings;
import net.talaatharb.imagegen.generation.sdcpp.SdCppPlatform;
import net.talaatharb.imagegen.mcp.ImageGenerationMcpServer;
import net.talaatharb.imagegen.model.GpuRuntime;
import net.talaatharb.imagegen.model.ModelStore;
import net.talaatharb.imagegen.service.ImageGenerationService;

/**
 * Hosts the task tabs (image generation today; editing and other image tasks get their own tabs) and the shared
 * status bar with GPU and localhost MCP switches.
 */
public class MainController {
    private final TaskRunner runner;
    private final AppSettings settings;
    private final ModelStore store;
    private final List<ImageGenerationService> generationServices;
    private final ImageGenerationMcpServer mcpServer;
    private final List<net.talaatharb.imagegen.service.ImageEditingService> editingServices;
    private final List<net.talaatharb.imagegen.service.ImageUpscalingService> upscalingServices;
    private final com.google.inject.Provider<ImageEditingPane> editingPane;
    private final com.google.inject.Provider<ImageUpscalingPane> upscalingPane;
    @FXML private javafx.scene.control.Tab editingTab;
    @FXML private javafx.scene.control.Tab upscalingTab;

    @FXML private Label statusLabel;
    @FXML private ProgressBar statusProgress;
    @FXML private Button cancelButton;
    @FXML private CheckBox gpuCheck;
    @FXML private ToggleButton mcpToggle;
    @FXML private Button mcpSettingsButton;

    @Inject
    public MainController(TaskRunner runner, AppSettings settings, ModelStore store,
            List<ImageGenerationService> generationServices, ImageGenerationMcpServer mcpServer,
            com.google.inject.Provider<ImageEditingPane> editingPane,
            com.google.inject.Provider<ImageUpscalingPane> upscalingPane,
            List<net.talaatharb.imagegen.service.ImageEditingService> editingServices,
            List<net.talaatharb.imagegen.service.ImageUpscalingService> upscalingServices) {
        this.runner = runner;
        this.settings = settings;
        this.store = store;
        this.generationServices = generationServices;
        this.mcpServer = mcpServer;
        this.editingPane = editingPane;
        this.upscalingPane = upscalingPane;
        this.editingServices = editingServices;
        this.upscalingServices = upscalingServices;
    }

    @FXML
    private void initialize() {
        editingTab.setContent(editingPane.get());
        upscalingTab.setContent(upscalingPane.get());
        statusLabel.textProperty().bind(runner.messageProperty());
        statusProgress.progressProperty().bind(runner.progressProperty());
        statusProgress.visibleProperty().bind(runner.runningProperty());
        cancelButton.disableProperty().bind(runner.runningProperty().not());
        mcpToggle.setSelected(false);
        mcpToggle.setTooltip(new Tooltip("Start a localhost-only MCP HTTP server. Always off at startup."));
        mcpSettingsButton.disableProperty().bind(mcpToggle.selectedProperty());
        if (SdCppPlatform.hasSeparateGpuBuild() || GpuRuntime.isSupported()) {
            gpuCheck.setSelected(GpuRuntime.isRequested());
            gpuCheck.disableProperty().bind(runner.runningProperty());
            gpuCheck.setTooltip(new Tooltip("Runs stable-diffusion.cpp models with its Vulkan build (any NVIDIA, AMD "
                    + "or Intel GPU) and, on Windows, the ONNX Runtime model through DirectML. The GPU builds are "
                    + "downloaded once (about 30 MB + 15 MB)."));
        } else if (SdCppPlatform.current(true).map(SdCppPlatform::gpu).orElse(false)) {
            gpuCheck.setSelected(true);
            gpuCheck.setDisable(true);
            gpuCheck.setTooltip(new Tooltip("stable-diffusion.cpp always uses Metal on this Mac"));
        } else {
            gpuCheck.setDisable(true);
            gpuCheck.setTooltip(new Tooltip("No prebuilt GPU runtime is available for this platform"));
        }

    }

    @FXML
    private void onMcpToggled() {
        if (!mcpToggle.isSelected()) {
            mcpServer.close();
            mcpToggle.setTooltip(new Tooltip("MCP server is off"));
            return;
        }

        try {
            mcpServer.start();
            mcpToggle.setTooltip(new Tooltip("MCP listening at " + mcpServer.endpoint()));
        } catch (IOException | IllegalArgumentException e) {
            mcpToggle.setSelected(false);
            org.slf4j.LoggerFactory.getLogger(MainController.class).warn("Could not start MCP server", e);
            UiSupport.error("MCP server could not start", e.getMessage());
        }
    }

    @FXML
    private void onMcpSettings() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(mcpToggle.getScene().getWindow());
        dialog.setTitle("MCP server settings");
        dialog.setHeaderText("Localhost only. Stop the server before changing settings.");
        Spinner<Integer> port;
        try {
            int configuredPort = settings.mcpPort();
            if (configuredPort < 1 || configuredPort > 65535) {
                throw new IllegalArgumentException("MCP port must be between 1 and 65535");
            }
            port = new Spinner<>(1, 65535, configuredPort);
        } catch (IllegalArgumentException e) {
            UiSupport.error("Invalid MCP settings", e.getMessage());
            return;
        }
        port.setEditable(true);
        ComboBox<ImageGenerationService> model = new ComboBox<>();
        model.getItems().setAll(generationServices);
        model.setConverter(new StringConverter<>() {
            @Override public String toString(ImageGenerationService value) {
                return value == null ? "" : value.displayName();
            }
            @Override public ImageGenerationService fromString(String value) {
                throw new UnsupportedOperationException("Select a model from the list");
            }
        });
        generationServices.stream().filter(m -> m.id().equals(settings.mcpModel())).findFirst()
                .ifPresent(model::setValue);
        GridPane fields = new GridPane();
        fields.setHgap(12);
        fields.setVgap(12);
        fields.addRow(0, new Label("Port:"), port);
        fields.addRow(1, new Label("Default model:"), model);
        var editModel = UiSupport.modelCombo(editingServices.stream()
                .filter(s -> s.supports(net.talaatharb.imagegen.service.ImageEditingRequest.Mode.IMG2IMG)).toList());
        var instructionModel = UiSupport.modelCombo(editingServices.stream()
                .filter(s -> s.supports(net.talaatharb.imagegen.service.ImageEditingRequest.Mode.INSTRUCTION)).toList());
        var upscaleModel = UiSupport.modelCombo(upscalingServices);
        selectConfigured(editModel, settings.mcpEditingModel());
        selectConfigured(instructionModel, settings.mcpInstructionModel());
        selectConfigured(upscaleModel, settings.mcpUpscalingModel());
        ComboBox<String> editMode = new ComboBox<>();
        editMode.getItems().setAll("img2img", "inpaint", "instruction");
        editMode.setValue(settings.mcpEditingMode());
        Spinner<Double> editStrength = new Spinner<>(0.05, 1.0, (double) settings.mcpEditingStrength(), 0.05);
        Spinner<Integer> upscaleTile = new Spinner<>(32, 256, settings.mcpUpscalingTileSize(), 32);
        fields.addRow(2, new Label("Img2img / inpainting model:"), editModel);
        fields.addRow(3, new Label("Instruction editing model:"), instructionModel);
        fields.addRow(4, new Label("Default editing mode:"), editMode);
        fields.addRow(5, new Label("Editing strength:"), editStrength);
        fields.addRow(6, new Label("Upscaling model (4x):"), upscaleModel);
        fields.addRow(7, new Label("Upscaling tile size:"), upscaleTile);
        dialog.getDialogPane().setContent(fields);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        javafx.scene.Node ok = dialog.getDialogPane().lookupButton(ButtonType.OK);
        ok.disableProperty().bind(model.valueProperty().isNull());
        ok.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            try {
                int value = Integer.parseInt(port.getEditor().getText().trim());
                if (value < 1 || value > 65535) {
                    throw new IllegalArgumentException("Port must be between 1 and 65535");
                }
                port.getValueFactory().setValue(value);
            } catch (IllegalArgumentException e) {
                event.consume();
                UiSupport.error("Invalid MCP port", "Enter a port between 1 and 65535");
            }
        });
        dialog.showAndWait().filter(ButtonType.OK::equals)
                .ifPresent(ignored -> {
                    settings.setMcpConfiguration(port.getValue(), model.getValue().id());
                    settings.setMcpTaskDefaults(editModel.getValue().id(), instructionModel.getValue().id(),
                            editMode.getValue(), editStrength.getValue().floatValue(),
                            upscaleModel.getValue().id(), upscaleTile.getValue());
                });
    }

    @FXML
    private void onCancel() {
        runner.cancel();
    }

    private static <T extends net.talaatharb.imagegen.service.ImageModelService> void selectConfigured(
            ComboBox<T> combo, String id) {
        combo.setValue(combo.getItems().stream().filter(m -> m.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown configured model: " + id)));
    }

    public javafx.beans.property.ReadOnlyBooleanProperty mcpEnabledProperty() {
        return mcpToggle.selectedProperty();
    }

    public void setMcpEnabled(boolean enabled) {
        mcpToggle.setSelected(enabled);
        onMcpToggled();
    }

    public void showMcpSettings() {
        if (!mcpServer.isRunning()) {
            onMcpSettings();
        }
    }

    @FXML
    private void onGpuToggled() {
        boolean wanted = gpuCheck.isSelected();
        if (!wanted) {
            apply(false);
            return;
        }
        if (!GpuRuntime.isSupported() || GpuRuntime.isInstalled(store)) {
            enableInstalledRuntime();
            return;
        }
        Alert confirm = new Alert(AlertType.CONFIRMATION,
                "GPU mode uses the Vulkan build of stable-diffusion.cpp (about 30 MB, fetched on the next "
                        + "generation) and the DirectML build of ONNX Runtime (about 15 MB, downloaded now from "
                        + "Microsoft's NuGet packages). Continue?", ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText("Enable GPU acceleration");
        if (confirm.showAndWait().filter(ButtonType.OK::equals).isEmpty()) {
            gpuCheck.setSelected(false);
            return;
        }
        boolean started = runner.submit("Installing the GPU runtime", progress -> {
            GpuRuntime.install(store, progress);
            return null;
        }, ignored -> enableInstalledRuntime(), error -> gpuCheck.setSelected(false));
        if (!started) {
            gpuCheck.setSelected(false);
        }
    }

    private void enableInstalledRuntime() {
        if (!GpuRuntime.isSupported() || GpuRuntime.activate(store)) {
            apply(true);
            return;
        }
        // ONNX Runtime's CPU build is already loaded in this process and can't be replaced; stable-diffusion.cpp
        // switches to its GPU build right away.
        apply(true);
        Alert info = new Alert(AlertType.INFORMATION,
                "stable-diffusion.cpp models now use the GPU. Restart Image Gen Studio to run the ONNX Runtime "
                        + "model on the GPU as well.");
        info.setHeaderText("Restart required for ONNX Runtime");
        info.showAndWait();
    }

    private void apply(boolean enabled) {
        GpuRuntime.setEnabled(enabled);
        settings.setGpuEnabled(enabled);
        // Loaded models keep their device; release them so the next run opens them with the new setting.
        generationServices.forEach(ImageGenerationService::close);
    }
}
