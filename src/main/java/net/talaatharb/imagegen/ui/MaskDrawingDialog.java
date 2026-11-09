package net.talaatharb.imagegen.ui;

import java.awt.image.BufferedImage;
import javafx.geometry.Insets;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

final class MaskDrawingDialog extends Dialog<BufferedImage> {
    private double lastX;
    private double lastY;

    MaskDrawingDialog(Window owner, BufferedImage input, BufferedImage existing) {
        initOwner(owner);
        setTitle("Draw inpainting mask");
        setHeaderText("Paint the area to edit (red overlay). Unpainted areas are preserved.");
        MaskPainter painter = new MaskPainter(input.getWidth(), input.getHeight(), existing);
        double scale = Math.min(1, Math.min(800.0 / input.getWidth(), 550.0 / input.getHeight()));
        Canvas canvas = new Canvas(input.getWidth() * scale, input.getHeight() * scale);
        canvas.setId("maskCanvas");
        var background = UiSupport.toFxImage(input);
        Runnable redraw = () -> {
            var graphics = canvas.getGraphicsContext2D();
            graphics.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
            graphics.drawImage(background, 0, 0, canvas.getWidth(), canvas.getHeight());
            graphics.drawImage(UiSupport.toFxImage(painter.overlay()), 0, 0, canvas.getWidth(), canvas.getHeight());
        };
        Slider brush = new Slider(2, 100, 24);
        brush.setId("maskBrushSize");
        brush.setPrefWidth(200);
        Label size = new Label();
        size.textProperty().bind(brush.valueProperty().asString("Brush: %.0f screen px"));
        CheckBox erase = new CheckBox("Eraser");
        erase.setId("maskEraser");
        Button clear = new Button("Clear mask");
        clear.setId("maskClear");
        clear.setOnAction(e -> {
            painter.clear();
            redraw.run();
        });
        canvas.setOnMousePressed(e -> {
            if (e.getButton() != MouseButton.PRIMARY) { return; }
            lastX = e.getX() / scale;
            lastY = e.getY() / scale;
            painter.stroke(lastX, lastY, lastX, lastY, (float) (brush.getValue() / scale), erase.isSelected());
            redraw.run();
        });
        canvas.setOnMouseDragged(e -> {
            if (!e.isPrimaryButtonDown()) { return; }
            double x = e.getX() / scale;
            double y = e.getY() / scale;
            painter.stroke(lastX, lastY, x, y, (float) (brush.getValue() / scale), erase.isSelected());
            lastX = x;
            lastY = y;
            redraw.run();
        });
        ScrollPane scroll = new ScrollPane(canvas);
        scroll.setPrefViewportWidth(canvas.getWidth());
        scroll.setPrefViewportHeight(canvas.getHeight());
        VBox content = new VBox(10, new HBox(12, size, brush, erase, clear), scroll);
        content.setPadding(new Insets(8));
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        ((Button) getDialogPane().lookupButton(ButtonType.OK)).setText("Use mask");
        setResultConverter(button -> button == ButtonType.OK ? painter.image() : null);
        redraw.run();
    }
}
