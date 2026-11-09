package net.talaatharb.imagegen.ui;

import java.util.List;
import com.google.inject.Inject;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import net.talaatharb.imagegen.config.AppSettings;
import net.talaatharb.imagegen.service.ImageUpscalingService;

public final class ImageUpscalingPane extends ImageTaskPane {
    @Inject
    public ImageUpscalingPane(TaskRunner runner, AppSettings settings, List<ImageUpscalingService> services) {
        super(runner);
        setId("upscalingPane");
        ComboBox<ImageUpscalingService> model = UiSupport.modelCombo(services);
        model.setId("upscaleModel");
        model.setValue(services.stream().filter(s -> s.id().equals(settings.mcpUpscalingModel())).findFirst()
                .orElse(services.get(0)));
        addModelActions(model);
        UiSupport.updateStatus(model.getValue(), status);
        model.valueProperty().addListener((o, old, value) -> UiSupport.updateStatus(value, status));
        Spinner<Integer> tile = new Spinner<>(32, 256, settings.mcpUpscalingTileSize(), 32);
        Button upscale = new Button("Upscale 4x");
        upscale.setId("upscaleButton");
        upscale.getStyleClass().add("primary");
        upscale.disableProperty().bind(runner.runningProperty());
        upscale.setOnAction(e -> {
            if (input == null) {
                UiSupport.error("Input image required", "Open an input image first");
                return;
            }
            if ((long) input.getWidth() * input.getHeight() * 16 > 16_777_216) {
                UiSupport.error("Image is too large", "4x output must fit within 16 megapixels");
                return;
            }
            var source = input;
            var service = model.getValue();
            int tileSize = tile.getValue();
            if (!UiSupport.confirmDownload(service)) { return; }
            runner.submit("Upscaling with " + service.displayName(), progress -> {
                service.install(progress);
                return service.upscale(source, tileSize, progress);
            }, image -> {
                UiSupport.updateStatus(service, status);
                showResult(image.image(), image.image().getWidth() + "x" + image.image().getHeight()
                        + " (4x), " + image.elapsed().toSeconds() + " s");
            }, error -> UiSupport.updateStatus(service, status));
        });
        controls.getChildren().addAll(new Label("4x neural upscaling (16 megapixel output limit)"),
                new Label("Tile size (smaller uses less memory):"), tile, upscale);
    }
}
