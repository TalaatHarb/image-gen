package net.talaatharb.imagegen.ui;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import com.google.inject.Inject;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import net.talaatharb.imagegen.service.GeneratedImage;
import net.talaatharb.imagegen.service.GenerationDefaults;
import net.talaatharb.imagegen.service.ImageGenerationRequest;
import net.talaatharb.imagegen.service.ImageGenerationService;
import net.talaatharb.imagegen.service.PreviewListener;

/**
 * Text to image: pick a model, describe the picture, tune size / steps / guidance / seed and generate. Intermediate
 * previews are shown while sampling and every result of the session is kept in a thumbnail strip.
 */
public class ImageGenerationTabController {
    private static final int THUMBNAIL_SIZE = 72;
    /** Previews arrive faster than they are useful; skip the ones that come within this interval. */
    private static final long PREVIEW_INTERVAL_MILLIS = 150;

    private final List<ImageGenerationService> services;
    private final TaskRunner runner;
    private GeneratedImage result;

    @FXML private ComboBox<ImageGenerationService> engineCombo;
    @FXML private Label engineDescription;
    @FXML private Label modelStatus;
    @FXML private TextArea promptInput;
    @FXML private TextArea negativeInput;
    @FXML private ComboBox<Integer> widthCombo;
    @FXML private ComboBox<Integer> heightCombo;
    @FXML private Spinner<Integer> stepsSpinner;
    @FXML private Spinner<Double> guidanceSpinner;
    @FXML private TextField seedField;
    @FXML private CheckBox randomSeedCheck;
    @FXML private Button generateButton;
    @FXML private StackPane imagePane;
    @FXML private ImageView imageView;
    @FXML private Label placeholder;
    @FXML private Button saveButton;
    @FXML private Button copyButton;
    @FXML private Button reuseSeedButton;
    @FXML private Label resultInfo;
    @FXML private HBox historyBox;

    @Inject
    public ImageGenerationTabController(List<ImageGenerationService> services, TaskRunner runner) {
        this.services = services;
        this.runner = runner;
    }

    @FXML
    private void initialize() {
        engineCombo.getItems().setAll(services);
        engineCombo.setCellFactory(list -> new ServiceCell());
        engineCombo.setButtonCell(new ServiceCell());
        engineCombo.valueProperty().addListener((obs, old, now) -> engineChanged(now));
        stepsSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 50, 4));
        guidanceSpinner.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(1, 20, 7, 0.5));
        stepsSpinner.setEditable(true);
        guidanceSpinner.setEditable(true);
        seedField.disableProperty().bind(randomSeedCheck.selectedProperty());
        seedField.textProperty().addListener((obs, old, now) -> {
            if (!now.matches("\\d{0,10}")) {
                seedField.setText(old);
            }
        });
        generateButton.disableProperty().bind(runner.runningProperty());
        imageView.fitWidthProperty().bind(imagePane.widthProperty().subtract(16));
        imageView.fitHeightProperty().bind(imagePane.heightProperty().subtract(16));
        guidanceSpinner.setTooltip(new Tooltip("How strictly the image follows the prompt (classifier-free "
                + "guidance). Distilled few-step models ignore it."));
        engineCombo.getSelectionModel().selectFirst();
        setResult(null);
    }

    private void engineChanged(ImageGenerationService service) {
        if (service == null) {
            return;
        }
        engineDescription.setText(service.description());
        UiSupport.updateStatus(service, modelStatus);
        GenerationDefaults defaults = service.defaults();
        widthCombo.getItems().setAll(defaults.sizes());
        heightCombo.getItems().setAll(defaults.sizes());
        widthCombo.setValue(defaults.width());
        heightCombo.setValue(defaults.height());
        stepsSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, defaults.maxSteps(),
                defaults.steps()));
        guidanceSpinner.getValueFactory().setValue((double) defaults.guidanceScale());
        guidanceSpinner.setDisable(!defaults.supportsGuidance());
        negativeInput.setDisable(!defaults.supportsNegativePrompt());
        negativeInput.setPromptText(defaults.supportsNegativePrompt() ? "What to avoid (optional)"
                : "This model does not use a negative prompt");
    }

    private ImageGenerationRequest buildRequest() {
        ImageGenerationService service = engineCombo.getValue();
        long seed = -1;
        if (!randomSeedCheck.isSelected()) {
            String text = seedField.getText();
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("Enter a seed or tick \"Random\"");
            }
            seed = Long.parseLong(text.trim());
        }
        float guidance = service.defaults().supportsGuidance() ? guidanceSpinner.getValue().floatValue()
                : service.defaults().guidanceScale();
        return new ImageGenerationRequest(promptInput.getText() == null ? "" : promptInput.getText(),
                negativeInput.isDisabled() ? "" : negativeInput.getText(), widthCombo.getValue(),
                heightCombo.getValue(), stepsSpinner.getValue(), guidance, seed);
    }

    @FXML
    private void onInstall() {
        UiSupport.install(runner, engineCombo.getValue(), modelStatus);
    }

    @FXML
    private void onGenerate() {
        ImageGenerationService service = engineCombo.getValue();
        if (service == null) {
            return;
        }
        ImageGenerationRequest request;
        try {
            request = buildRequest();
        } catch (IllegalArgumentException e) {
            UiSupport.error("Check the settings", e.getMessage());
            return;
        }
        if (!UiSupport.confirmDownload(service)) {
            return;
        }
        AtomicLong lastPreview = new AtomicLong();
        PreviewListener preview = image -> {
            long now = System.currentTimeMillis();
            if (now - lastPreview.get() < PREVIEW_INTERVAL_MILLIS) {
                return;
            }
            lastPreview.set(now);
            Image fx = UiSupport.toFxImage(image);
            Platform.runLater(() -> showImage(fx));
        };
        runner.submit("Generating with " + service.displayName(), progress -> {
            if (!service.isInstalled()) {
                service.install(progress);
            }
            return service.generate(request, progress, preview);
        }, generated -> {
            UiSupport.updateStatus(service, modelStatus);
            setResult(generated);
            addToHistory(generated);
        }, error -> {
            UiSupport.updateStatus(service, modelStatus);
            setResult(result);
        });
    }

    private void showImage(Image image) {
        imageView.setImage(image);
        placeholder.setVisible(image == null);
    }

    private void setResult(GeneratedImage generated) {
        result = generated;
        boolean none = generated == null;
        saveButton.setDisable(none);
        copyButton.setDisable(none);
        reuseSeedButton.setDisable(none);
        showImage(none ? null : UiSupport.toFxImage(generated.image()));
        if (none) {
            resultInfo.setText("");
            return;
        }
        ImageGenerationRequest r = generated.request();
        resultInfo.setText(String.format(Locale.ROOT, "%dx%d, %d steps, seed %d, %.1f s", r.width(), r.height(),
                r.steps(), r.seed(), generated.elapsed().toMillis() / 1000.0));
    }

    private void addToHistory(GeneratedImage generated) {
        ImageView thumbnail = new ImageView(UiSupport.toFxImage(generated.image()));
        thumbnail.setFitWidth(THUMBNAIL_SIZE);
        thumbnail.setFitHeight(THUMBNAIL_SIZE);
        thumbnail.setPreserveRatio(true);
        thumbnail.setSmooth(true);
        thumbnail.getStyleClass().add("thumbnail");
        Tooltip.install(thumbnail, new Tooltip(generated.request().prompt()));
        thumbnail.setOnMouseClicked(e -> setResult(generated));
        historyBox.getChildren().add(0, thumbnail);
    }

    @FXML
    private void onSave() {
        if (result != null) {
            UiSupport.savePng(imageView.getScene().getWindow(), result.image(),
                    result.modelId() + "-" + result.request().seed() + ".png");
        }
    }

    @FXML
    private void onCopy() {
        if (result != null) {
            ClipboardContent content = new ClipboardContent();
            content.putImage(UiSupport.toFxImage(result.image()));
            Clipboard.getSystemClipboard().setContent(content);
        }
    }

    @FXML
    private void onReuseSeed() {
        if (result != null) {
            randomSeedCheck.setSelected(false);
            seedField.setText(Long.toString(result.request().seed()));
        }
    }

    private static final class ServiceCell extends ListCell<ImageGenerationService> {
        @Override
        protected void updateItem(ImageGenerationService item, boolean empty) {
            super.updateItem(item, empty);
            setText(empty || item == null ? null : item.displayName());
        }
    }
}
