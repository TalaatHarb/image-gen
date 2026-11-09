package net.talaatharb.imagegen.ui;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.*;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;
import net.talaatharb.imagegen.ImageGenApp;
import net.talaatharb.imagegen.config.*;
import net.talaatharb.imagegen.mcp.ImageGenerationMcpServer;
import net.talaatharb.imagegen.model.*;
import net.talaatharb.imagegen.service.*;

@EnabledIfSystemProperty(named = "imagegen.integration", matches = "true")
class ImageTasksMcpIntegrationTest {
    @TempDir Path dir;
    private static <T> T fx(Callable<T> work) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try { result.complete(work.call()); }
            catch (Throwable e) { result.completeExceptionally(e); }
        });
        return result.get(30, TimeUnit.SECONDS);
    }

    private static BufferedImage source(int size) {
        var image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setColor(Color.GREEN);
        g.fillRect(0, 0, size, size);
        g.setColor(Color.ORANGE);
        g.fillOval(size / 4, size / 4, size / 2, size / 2);
        g.dispose();
        return image;
    }

    private static String base64(BufferedImage image) throws Exception {
        var png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        return Base64.getEncoder().encodeToString(png.toByteArray());
    }

    private static BufferedImage call(ImageGenerationMcpServer server, String name, String arguments,
            String artifact) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(server.endpoint())).timeout(Duration.ofMinutes(5))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"%s","arguments":%s}}
                        """.formatted(name, arguments))).build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        JsonNode body = new ObjectMapper().readTree(response.body());
        assertFalse(body.has("error"), body.toString());
        JsonNode result = body.path("result");
        assertFalse(result.path("isError").asBoolean(), result.toString());
        byte[] bytes = Base64.getDecoder().decode(result.path("content").get(1).path("data").asText());
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        assertNotNull(image);
        Path output = Path.of("target", "integration-images");
        Files.createDirectories(output);
        Files.write(output.resolve(artifact + ".png"), bytes);
        System.out.println(name + ": " + result.path("content").get(0).path("text").asText());
        return image;
    }

    @Test
    void newTabsAndHttpToolsWorkWithRealModels() throws Exception {
        var settings = new AppSettings(dir.resolve("settings.properties"));
        try (var socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            settings.setMcpConfiguration(socket.getLocalPort(), "sd15-lcm");
        }
        var injector = Guice.createInjector(new AppModule(ModelStore.defaultRoot(), settings));
        var editors = injector.getInstance(Key.get(new TypeLiteral<List<ImageEditingService>>() {}));
        var upscalers = injector.getInstance(Key.get(new TypeLiteral<List<ImageUpscalingService>>() {}));
        editors.stream().filter(s -> s.id().equals("sd15")).findFirst().orElseThrow().install(ProgressListener.NONE);
        upscalers.get(0).install(ProgressListener.NONE);
        CompletableFuture<Void> started = new CompletableFuture<>();
        Platform.startup(() -> started.complete(null));
        started.get(30, TimeUnit.SECONDS);
        var server = injector.getInstance(ImageGenerationMcpServer.class);
        try {
            Parent root = fx(() -> {
                var loader = new FXMLLoader(ImageGenApp.class.getResource("ui/main.fxml"));
                loader.setControllerFactory(injector::getInstance);
                Parent loaded = loader.load();
                Stage stage = new Stage();
                stage.setScene(new Scene(loaded, 1280, 820));
                stage.show();
                var tabs = (TabPane) loaded.lookup("#taskTabs");
                assertEquals(List.of("Image Generation", "Image Editing", "Upscaling"),
                        tabs.getTabs().stream().map(Tab::getText).toList());
                var editor = (ImageEditingPane) tabs.getTabs().get(1).getContent();
                @SuppressWarnings("unchecked")
                var mode = (ComboBox<ImageEditingRequest.Mode>) editor.lookup("#editMode");
                mode.setValue(ImageEditingRequest.Mode.INSTRUCTION);
                var models = (ComboBox<?>) editor.lookup("#editModel");
                assertEquals("flux2-klein-4b", ((ImageEditingService) models.getValue()).id());
                mode.setValue(ImageEditingRequest.Mode.IMG2IMG);
                tabs.getSelectionModel().select(2);
                var upscale = (ImageUpscalingPane) tabs.getTabs().get(2).getContent();
                upscale.setInput(source(64));
                ((Button) upscale.lookup("#upscaleButton")).fire();
                return loaded;
            });
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
            while (fx(() -> injector.getInstance(TaskRunner.class).isRunning()) && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
            assertFalse(fx(() -> injector.getInstance(TaskRunner.class).isRunning()));
            fx(() -> {
                var tabs = (TabPane) root.lookup("#taskTabs");
                var pane = (ImageUpscalingPane) tabs.getTabs().get(2).getContent();
                var result = (javafx.scene.image.ImageView) pane.lookup("#taskResultImage");
                assertNotNull(result.getImage(), "Upscaling tab did not show an image");
                assertEquals(256, result.getImage().getWidth());
                tabs.getSelectionModel().select(1);
                var edit = (ImageEditingPane) tabs.getTabs().get(1).getContent();
                edit.setInput(source(256));
                ((TextArea) edit.lookup("#editPrompt")).setText("an orange on green grass");
                @SuppressWarnings("unchecked")
                var width = (ComboBox<Integer>) edit.lookup("#editWidth");
                @SuppressWarnings("unchecked")
                var height = (ComboBox<Integer>) edit.lookup("#editHeight");
                @SuppressWarnings("unchecked")
                var steps = (Spinner<Integer>) edit.lookup("#editSteps");
                width.setValue(256);
                height.setValue(256);
                steps.getValueFactory().setValue(4);
                ((Button) edit.lookup("#editButton")).fire();
                return null;
            });
            deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
            while (fx(() -> injector.getInstance(TaskRunner.class).isRunning()) && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
            assertFalse(fx(() -> injector.getInstance(TaskRunner.class).isRunning()));
            fx(() -> {
                var tabs = (TabPane) root.lookup("#taskTabs");
                var pane = (ImageEditingPane) tabs.getTabs().get(1).getContent();
                var result = (javafx.scene.image.ImageView) pane.lookup("#taskResultImage");
                assertNotNull(result.getImage(), "Editing tab did not show an image");
                assertEquals(256, result.getImage().getWidth());
                ((ToggleButton) root.lookup("#mcpToggle")).fire();
                return null;
            });
            assertTrue(server.isRunning());
            String image = base64(source(256));
            for (String mode : List.of("default", "inpaint")) {
                var mask = new BufferedImage(256, 256, BufferedImage.TYPE_BYTE_GRAY);
                var g = mask.createGraphics();
                g.setColor(Color.WHITE);
                g.fillRect(128, 0, 128, 256);
                g.dispose();
                BufferedImage edited = call(server, "edit_image", """
                        {"image":"%s","mask":"%s","prompt":"an orange on grass","model":"default","mode":"%s",
                         "width":"256","height":"256","steps":4,"strength":"default","seed":42}
                        """.formatted(image, base64(mask), mode), "mcp-edit-" + mode);
                assertEquals(256, edited.getWidth());
                assertNotEquals(source(256).getRGB(180, 100), edited.getRGB(180, 100));
            }
            var upscaled = call(server, "upscale_image", """
                    {"image":"%s","model":"default","tile_size":"default"}
                    """.formatted(base64(source(64))), "mcp-upscaled");
            assertEquals(256, upscaled.getWidth());
        } finally {
            server.close();
            injector.getInstance(TaskRunner.class).shutdown();
            Platform.exit();
        }
    }
}
