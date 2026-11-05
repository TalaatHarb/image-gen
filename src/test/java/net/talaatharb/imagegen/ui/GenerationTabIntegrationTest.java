package net.talaatharb.imagegen.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.TypeLiteral;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.stage.Stage;
import net.talaatharb.imagegen.ImageGenApp;
import net.talaatharb.imagegen.config.AppModule;
import net.talaatharb.imagegen.config.AppSettings;
import net.talaatharb.imagegen.model.ModelStore;
import net.talaatharb.imagegen.service.ImageGenerationService;

/**
 * Opens the real window and clicks Generate with the default (SDXS) model; enabled with
 * {@code -Dimagegen.integration=true}. Downloads the model and stable-diffusion.cpp if they are missing.
 */
@EnabledIfSystemProperty(named = "imagegen.integration", matches = "true")
class GenerationTabIntegrationTest {

    @TempDir
    Path settingsDir;

    private static <T> T onFx(Callable<T> action) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(action.call());
            } catch (Throwable e) {
                result.completeExceptionally(e);
            }
        });
        return result.get(30, TimeUnit.SECONDS);
    }

    @Test
    void generatesAnImageFromTheTab() throws Exception {
        Injector injector = Guice.createInjector(new AppModule(ModelStore.defaultRoot(),
                new AppSettings(settingsDir.resolve("settings.properties"))));
        CompletableFuture<Void> started = new CompletableFuture<>();
        Platform.startup(() -> started.complete(null));
        started.get(30, TimeUnit.SECONDS);
        try {
            Parent root = onFx(() -> {
                FXMLLoader loader = new FXMLLoader(ImageGenApp.class.getResource("ui/main.fxml"));
                loader.setControllerFactory(injector::getInstance);
                Parent loaded = loader.load();
                Stage stage = new Stage();
                stage.setScene(new Scene(loaded, 1280, 820));
                stage.show();
                return loaded;
            });
            String engine = onFx(() -> {
                ImageGenerationService selected = (ImageGenerationService) ((ComboBox<?>) root
                        .lookup("#engineCombo")).getValue();
                ((Button) root.lookup("#generateButton")).fire();
                return selected.id();
            });
            assertEquals("sdxs-512", engine);

            long deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(10);
            String info = "";
            while (System.currentTimeMillis() < deadline && info.isBlank()) {
                Thread.sleep(500);
                info = onFx(() -> {
                    String text = ((Label) root.lookup("#resultInfo")).getText();
                    return text == null ? "" : text;
                });
            }
            Image image = onFx(() -> ((ImageView) root.lookup("#imageView")).getImage());
            assertNotNull(image, "no image was shown");
            assertEquals(512, image.getWidth());
            assertTrue(info.contains("seed"), "result info: " + info);
            assertTrue(onFx(() -> !((Button) root.lookup("#saveButton")).isDisabled()));
            System.out.println("Tab result: " + info);
        } finally {
            injector.getInstance(TaskRunner.class).shutdown();
            injector.getInstance(Key.get(new TypeLiteral<List<ImageGenerationService>>() {
            })).forEach(ImageGenerationService::close);
            Platform.exit();
        }
    }
}
