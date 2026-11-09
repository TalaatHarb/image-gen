package net.talaatharb.imagegen.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.awt.image.BufferedImage;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "imagegen.integration", matches = "true")
class MaskDrawingDialogIntegrationTest {
    @Test
    void dialogScalesPreviewAndAppliesFullResolutionMaskWithoutMutatingOriginal() throws Exception {
        CompletableFuture<Void> result = new CompletableFuture<>();
        Platform.startup(() -> {
            try {
                BufferedImage image = new BufferedImage(1600, 800, BufferedImage.TYPE_INT_RGB);
                BufferedImage original = new BufferedImage(1600, 800, BufferedImage.TYPE_INT_RGB);
                original.setRGB(40, 40, 0xffffff);
                MaskDrawingDialog dialog = new MaskDrawingDialog(null, image, original);
                dialog.show();
                dialog.getDialogPane().applyCss();
                Canvas canvas = (Canvas) dialog.getDialogPane().lookup("#maskCanvas");
                assertEquals(800, canvas.getWidth());
                assertEquals(400, canvas.getHeight());
                assertNull(dialog.getResultConverter().call(ButtonType.CANCEL));
                ((Button) dialog.getDialogPane().lookup("#maskClear")).fire();
                BufferedImage applied = dialog.getResultConverter().call(ButtonType.OK);
                assertEquals(1600, applied.getWidth());
                assertEquals(800, applied.getHeight());
                assertEquals(0, applied.getRGB(40, 40) & 0xffffff);
                assertEquals(0xffffff, original.getRGB(40, 40) & 0xffffff);
                dialog.close();
                result.complete(null);
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        });
        try {
            result.get(30, TimeUnit.SECONDS);
        } finally {
            Platform.exit();
        }
    }
}
