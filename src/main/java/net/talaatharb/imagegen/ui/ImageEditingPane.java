package net.talaatharb.imagegen.ui;

import java.awt.image.BufferedImage;
import java.util.List;
import com.google.inject.Inject;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import net.talaatharb.imagegen.config.AppSettings;
import net.talaatharb.imagegen.service.GenerationDefaults;
import net.talaatharb.imagegen.service.ImageEditingRequest;
import net.talaatharb.imagegen.service.ImageEditingService;
import net.talaatharb.imagegen.service.ImageGenerationRequest;
import net.talaatharb.imagegen.service.PreviewListener;

public final class ImageEditingPane extends ImageTaskPane {
    private final ComboBox<ImageEditingRequest.Mode> mode = new ComboBox<>();
    private final ComboBox<ImageEditingService> model;
    private final ComboBox<Integer> width = new ComboBox<>();
    private final ComboBox<Integer> height = new ComboBox<>();
    private final Spinner<Integer> steps = new Spinner<>();
    private final Spinner<Double> guidance = new Spinner<>(0.0, 20.0, 7.0, 0.5);
    private final Spinner<Double> strength;
    private final TextArea prompt = new TextArea();
    private final TextArea negative = new TextArea();
    private final TextField seed = new TextField("-1");
    private final Label maskStatus = new Label("No mask (white = edit, black = preserve)");
    private BufferedImage mask;

    @Inject
    public ImageEditingPane(TaskRunner runner, AppSettings settings, List<ImageEditingService> services) {
        super(runner);
        setId("editingPane");
        strength = new Spinner<>(0.05, 1.0, (double) settings.mcpEditingStrength(), 0.05);
        mode.getItems().setAll(ImageEditingRequest.Mode.values());
        mode.setValue(java.util.Arrays.stream(ImageEditingRequest.Mode.values())
                .filter(m -> m.id().equals(settings.mcpEditingMode())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Invalid configured editing mode")));
        mode.setId("editMode");
        model = UiSupport.modelCombo(List.of());
        model.setId("editModel");
        width.setId("editWidth");
        height.setId("editHeight");
        steps.setId("editSteps");
        mode.disableProperty().bind(runner.runningProperty());
        controls.getChildren().addAll(new Label("Editing mode:"), mode);
        addModelActions(model);
        Button loadMask = new Button("Open inpainting mask...");
        loadMask.setOnAction(e -> {
            BufferedImage loaded = UiSupport.openImage(getScene().getWindow(), "Open mask (white = edit)");
            if (loaded != null) {
                mask = loaded;
                maskStatus.setText("Mask: " + mask.getWidth() + "x" + mask.getHeight() + " (white = edit)");
            }
        });
        loadMask.disableProperty().bind(mode.valueProperty().isNotEqualTo(ImageEditingRequest.Mode.INPAINT));
        Button drawMask = new Button("Draw mask...");
        drawMask.setId("drawMaskButton");
        drawMask.disableProperty().bind(mode.valueProperty().isNotEqualTo(ImageEditingRequest.Mode.INPAINT)
                .or(runner.runningProperty()));
        drawMask.setOnAction(e -> {
            if (input == null) {
                UiSupport.error("Input image required", "Open an input image before drawing a mask");
                return;
            }
            try {
                new MaskDrawingDialog(getScene().getWindow(), input, mask).showAndWait().ifPresent(drawn -> {
                    mask = drawn;
                    maskStatus.setText("Drawn mask: " + mask.getWidth() + "x" + mask.getHeight() + " (white = edit)");
                });
            } catch (IllegalArgumentException error) {
                UiSupport.error("Cannot draw this mask", error.getMessage());
            }
        });
        prompt.setPrefRowCount(4);
        prompt.setPromptText("Describe the desired image, or an instruction in instruction mode");
        prompt.setId("editPrompt");
        negative.setPrefRowCount(2);
        Button edit = new Button("Edit image");
        edit.setId("editButton");
        edit.getStyleClass().add("primary");
        edit.disableProperty().bind(runner.runningProperty());
        edit.setOnAction(e -> runEdit());
        controls.getChildren().addAll(loadMask, drawMask, maskStatus,
                new Label("Prompt / instruction:"), prompt, new Label("Negative prompt:"), negative,
                new Label("Output width (input will be resized):"), width, new Label("Output height:"), height,
                new Label("Steps:"), steps, new Label("Guidance:"), guidance, new Label("Strength:"), strength,
                new Label("Seed (-1 = random):"), seed, edit);
        model.valueProperty().addListener((o, old, selected) -> {
            if (selected == null) { return; }
            GenerationDefaults d = selected.defaults();
            width.getItems().setAll(d.sizes());
            height.getItems().setAll(d.sizes());
            width.setValue(d.width());
            height.setValue(d.height());
            steps.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, d.maxSteps(), d.steps()));
            guidance.getValueFactory().setValue((double) d.guidanceScale());
            guidance.setDisable(!d.supportsGuidance());
            negative.setDisable(!d.supportsNegativePrompt());
            UiSupport.updateStatus(selected, status);
        });
        Runnable select = () -> {
            var currentMode = mode.getValue();
            model.getItems().setAll(services.stream().filter(s -> s.supports(currentMode)).toList());
            String chosen = currentMode == ImageEditingRequest.Mode.INSTRUCTION
                    ? settings.mcpInstructionModel() : settings.mcpEditingModel();
            model.setValue(model.getItems().stream().filter(s -> s.id().equals(chosen)).findFirst()
                    .orElse(model.getItems().get(0)));
            strength.setDisable(currentMode == ImageEditingRequest.Mode.INSTRUCTION);
        };
        mode.valueProperty().addListener((o, old, value) -> select.run());
        select.run();
    }

    @Override protected void setInput(BufferedImage image) {
        super.setInput(image);
        mask = null;
        maskStatus.setText("No mask (white = edit, black = preserve)");
    }

    private void runEdit() {
        ImageEditingService service = model.getValue();
        ImageEditingRequest request;
        try {
            var d = service.defaults();
            var generation = new ImageGenerationRequest(prompt.getText(), d.supportsNegativePrompt() ? negative.getText() : "",
                    width.getValue(), height.getValue(), steps.getValue(), guidance.getValue().floatValue(),
                    Long.parseLong(seed.getText().trim()));
            if (input == null) { throw new IllegalArgumentException("Open an input image first"); }
            request = new ImageEditingRequest(input, mask, mode.getValue(), generation, strength.getValue().floatValue());
        } catch (IllegalArgumentException e) {
            UiSupport.error("Check editing settings", e.getMessage());
            return;
        }
        if (!UiSupport.confirmDownload(service)) { return; }
        runner.submit("Editing with " + service.displayName(), progress -> {
            service.install(progress);
            return service.edit(request, progress, PreviewListener.NONE);
        }, image -> {
            UiSupport.updateStatus(service, status);
            showResult(image.image(), image.image().getWidth() + "x" + image.image().getHeight()
                    + ", seed " + image.request().seed() + ", " + image.elapsed().toSeconds() + " s");
        }, error -> UiSupport.updateStatus(service, status));
    }
}
