package net.talaatharb.imagegen.ui;

import java.awt.image.BufferedImage;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** Common input/output actions for image-to-image tasks. */
abstract class ImageTaskPane extends SplitPane {
    protected final TaskRunner runner;
    protected final VBox controls = new VBox(10);
    protected final Label status = new Label();
    protected final ImageView inputPreview = new ImageView();
    protected BufferedImage input;
    private BufferedImage output;
    private final ImageView result = new ImageView();
    private final Label resultInfo = new Label("Load an input image to begin");
    private final Button save = new Button("Save PNG...");
    private final Button copy = new Button("Copy");
    private final Button reuse = new Button("Use as input");

    ImageTaskPane(TaskRunner runner) {
        this.runner = runner;
        setDividerPositions(0.36);
        controls.setPadding(new Insets(12));
        controls.setMinWidth(340);
        ScrollPane settings = new ScrollPane(controls);
        settings.setFitToWidth(true);
        Button load = new Button("Open input image...");
        load.setOnAction(e -> {
            BufferedImage image = UiSupport.openImage(getScene().getWindow(), "Open input image");
            if (image != null) { setInput(image); }
        });
        inputPreview.setFitWidth(280);
        inputPreview.setFitHeight(160);
        inputPreview.setPreserveRatio(true);
        controls.getChildren().addAll(load, inputPreview);
        StackPane canvas = new StackPane(result);
        canvas.getStyleClass().add("canvas");
        result.setPreserveRatio(true);
        result.setId("taskResultImage");
        resultInfo.setId("taskResultInfo");
        result.fitWidthProperty().bind(canvas.widthProperty().subtract(20));
        result.fitHeightProperty().bind(canvas.heightProperty().subtract(20));
        save.setDisable(true);
        copy.setDisable(true);
        reuse.setDisable(true);
        save.setOnAction(e -> UiSupport.savePng(getScene().getWindow(), output, "processed-image.png"));
        copy.setOnAction(e -> {
            ClipboardContent data = new ClipboardContent();
            data.putImage(UiSupport.toFxImage(output));
            Clipboard.getSystemClipboard().setContent(data);
        });
        reuse.setOnAction(e -> setInput(output));
        VBox bottom = new VBox(8, new HBox(8, save, copy, reuse), resultInfo);
        bottom.setPadding(new Insets(12));
        BorderPane preview = new BorderPane(canvas);
        preview.setBottom(bottom);
        getItems().addAll(settings, preview);
    }

    protected void setInput(BufferedImage image) {
        input = image;
        inputPreview.setImage(UiSupport.toFxImage(image));
    }

    protected void showResult(BufferedImage image, String info) {
        output = image;
        result.setImage(UiSupport.toFxImage(image));
        resultInfo.setText(info);
        save.setDisable(false);
        copy.setDisable(false);
        reuse.setDisable(false);
    }

    protected void addModelActions(ComboBox<? extends net.talaatharb.imagegen.service.ImageModelService> model) {
        Button download = new Button("Download model");
        download.disableProperty().bind(runner.runningProperty());
        download.setOnAction(e -> UiSupport.install(runner, model.getValue(), status));
        model.disableProperty().bind(runner.runningProperty());
        controls.getChildren().addAll(model, download, status);
    }
}
