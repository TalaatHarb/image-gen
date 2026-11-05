package net.talaatharb.imagegen.ui;

import java.util.List;

import com.google.inject.Inject;

import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tooltip;
import net.talaatharb.imagegen.config.AppSettings;
import net.talaatharb.imagegen.generation.sdcpp.SdCppPlatform;
import net.talaatharb.imagegen.model.GpuRuntime;
import net.talaatharb.imagegen.model.ModelStore;
import net.talaatharb.imagegen.service.ImageGenerationService;

/**
 * Hosts the task tabs (image generation today; editing and other image tasks get their own tabs) and the shared
 * status bar with the GPU switch.
 */
public class MainController {
    private final TaskRunner runner;
    private final AppSettings settings;
    private final ModelStore store;
    private final List<ImageGenerationService> generationServices;

    @FXML private Label statusLabel;
    @FXML private ProgressBar statusProgress;
    @FXML private Button cancelButton;
    @FXML private CheckBox gpuCheck;

    @Inject
    public MainController(TaskRunner runner, AppSettings settings, ModelStore store,
            List<ImageGenerationService> generationServices) {
        this.runner = runner;
        this.settings = settings;
        this.store = store;
        this.generationServices = generationServices;
    }

    @FXML
    private void initialize() {
        statusLabel.textProperty().bind(runner.messageProperty());
        statusProgress.progressProperty().bind(runner.progressProperty());
        statusProgress.visibleProperty().bind(runner.runningProperty());
        cancelButton.disableProperty().bind(runner.runningProperty().not());
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
    private void onCancel() {
        runner.cancel();
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
