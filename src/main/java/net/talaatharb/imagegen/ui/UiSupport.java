package net.talaatharb.imagegen.ui;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

import javax.imageio.ImageIO;

import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import net.talaatharb.imagegen.model.ModelStore;
import net.talaatharb.imagegen.service.ImageModelService;

/** Small helpers shared by the tab controllers. */
final class UiSupport {
    private static File lastDirectory;

    private UiSupport() {
    }

    static void error(String title, String message) {
        TaskRunner.runLater(() -> {
            Alert alert = new Alert(AlertType.ERROR, message);
            alert.setHeaderText(title);
            alert.showAndWait();
        });
    }

    /** Converts an AWT image to a JavaFX image without the javafx-swing module. */
    static Image toFxImage(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
        WritableImage result = new WritableImage(width, height);
        result.getPixelWriter().setPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width);
        return result;
    }

    static void savePng(Window owner, BufferedImage image, String suggestedName) {
        if (image == null) {
            return;
        }
        FileChooser chooser = chooser("Save image");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG image", "*.png"));
        chooser.setInitialFileName(suggestedName);
        File file = chooser.showSaveDialog(owner);
        if (file == null) {
            return;
        }
        lastDirectory = file.getParentFile();
        Path path = file.getName().toLowerCase(Locale.ROOT).endsWith(".png") ? file.toPath()
                : file.toPath().resolveSibling(file.getName() + ".png");
        try {
            ImageIO.write(image, "png", path.toFile());
        } catch (IOException e) {
            error("Could not save " + path.getFileName(), e.getMessage());
        }
    }

    private static FileChooser chooser(String title) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        if (lastDirectory != null && lastDirectory.isDirectory()) {
            chooser.setInitialDirectory(lastDirectory);
        }
        return chooser;
    }

    static String size(long bytes) {
        if (bytes >= 1L << 30) {
            return String.format(Locale.ROOT, "%.1f GB", bytes / (double) (1L << 30));
        }
        return String.format(Locale.ROOT, "%d MB", Math.max(1, bytes >> 20));
    }

    /** Asks before downloading a model that is not cached yet; returns false when the user declines. */
    static boolean confirmDownload(ImageModelService service) {
        if (service.isInstalled()) {
            return true;
        }
        long bytes = service.missingDownloadBytes();
        Alert alert = new Alert(AlertType.CONFIRMATION, service.description()
                + "\n\nAbout " + size(bytes) + " will be downloaded once and cached in "
                + ModelStore.defaultRoot() + ". Continue?", ButtonType.OK, ButtonType.CANCEL);
        alert.setHeaderText("Download " + service.displayName() + "?");
        return alert.showAndWait().filter(b -> b == ButtonType.OK).isPresent();
    }

    static void install(TaskRunner runner, ImageModelService service, Label status) {
        if (service == null || !confirmDownload(service)) {
            return;
        }
        runner.submit("Installing " + service.displayName(), progress -> {
            service.install(progress);
            return null;
        }, ignored -> updateStatus(service, status), error -> updateStatus(service, status));
    }

    static void updateStatus(ImageModelService service, Label status) {
        if (status != null && service != null) {
            status.setText(service.isInstalled() ? "Installed"
                    : "Not downloaded yet (" + size(service.missingDownloadBytes()) + ", downloaded on first use)");
        }
    }
}
