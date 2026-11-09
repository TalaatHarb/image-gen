package net.talaatharb.imagegen.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import javafx.scene.control.ToggleButton;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.stage.Stage;
import net.talaatharb.imagegen.ImageGenApp;
import net.talaatharb.imagegen.config.AppModule;
import net.talaatharb.imagegen.config.AppSettings;
import net.talaatharb.imagegen.mcp.ImageGenerationMcpServer;
import net.talaatharb.imagegen.model.ModelStore;
import net.talaatharb.imagegen.service.ImageGenerationService;

/**
 * Exercises the MCP toggle, generates through HTTP with SD 1.5 + LCM, and clicks Generate with SDXS.
 * Enabled with {@code -Dimagegen.integration=true}; download SD 1.5 + LCM in the app first.
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
        AppSettings settings = new AppSettings(settingsDir.resolve("settings.properties"));
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            settings.setMcpConfiguration(socket.getLocalPort(), "sd15-lcm");
        }
        Injector injector = Guice.createInjector(new AppModule(ModelStore.defaultRoot(), settings));
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
            checkMcp(root, injector);
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
            injector.getInstance(ImageGenerationMcpServer.class).close();
            injector.getInstance(TaskRunner.class).shutdown();
            injector.getInstance(Key.get(new TypeLiteral<List<ImageGenerationService>>() {
            })).forEach(ImageGenerationService::close);
            Platform.exit();
        }
    }

    private static JsonNode mcpCall(ImageGenerationMcpServer server, String method, String params) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(server.endpoint()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"jsonrpc":"2.0","id":1,"method":"%s","params":%s}
                        """.formatted(method, params))).build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return new ObjectMapper().readTree(response.body());
    }

    private static void checkMcp(Parent root, Injector injector) throws Exception {
        ImageGenerationMcpServer server = injector.getInstance(ImageGenerationMcpServer.class);
        TaskRunner runner = injector.getInstance(TaskRunner.class);
        assertFalse(server.isRunning());
        onFx(() -> {
            ToggleButton toggle = (ToggleButton) root.lookup("#mcpToggle");
            assertFalse(toggle.isSelected());
            toggle.fire();
            assertTrue(toggle.isSelected());
            assertTrue(root.lookup("#mcpSettingsButton").isDisabled());
            return null;
        });
        assertTrue(server.isRunning());
        assertEquals("image-gen", mcpCall(server, "initialize", """
                {"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1"}}
                """).path("result").path("serverInfo").path("name").asText());

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> busy = runner.submitRemote("Test busy worker", progress -> {
            started.countDown();
            release.await();
            return null;
        });
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS));
            JsonNode rejected = mcpCall(server, "tools/call", """
                    {"name":"generate_image","arguments":{"prompt":"fox"}}
                    """).path("result");
            assertTrue(rejected.path("isError").asBoolean());
            assertTrue(rejected.path("content").get(0).path("text").asText().contains("Busy"));
            onFx(() -> {
                assertTrue(root.lookup("#gpuCheck").isDisabled());
                ((Button) root.lookup("#cancelButton")).fire();
                return null;
            });
            assertThrowsCancellation(busy);
        } finally {
            release.countDown();
        }
        JsonNode generated = mcpCall(server, "tools/call", """
                {"name":"generate_image","arguments":{"prompt":"a fox sitting in a green meadow","seed":42}}
                """).path("result");
        assertFalse(generated.path("isError").asBoolean(), generated.toString());
        JsonNode metadata = new ObjectMapper().readTree(generated.path("content").get(0).path("text").asText());
        assertEquals("sd15-lcm", metadata.path("model").asText());
        assertEquals(42, metadata.path("seed").asLong());
        assertEquals("image/png", generated.path("content").get(1).path("mimeType").asText());
        byte[] png = java.util.Base64.getDecoder().decode(generated.path("content").get(1).path("data").asText());
        java.awt.image.BufferedImage decoded = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
        assertNotNull(decoded, "MCP returned an undecodable image");
        assertEquals(512, decoded.getWidth());
        assertEquals(512, decoded.getHeight());
        int darkest = 255;
        int lightest = 0;
        for (int y = 0; y < decoded.getHeight(); y += 4) {
            for (int x = 0; x < decoded.getWidth(); x += 4) {
                int rgb = decoded.getRGB(x, y);
                int brightness = (((rgb >> 16) & 255) + ((rgb >> 8) & 255) + (rgb & 255)) / 3;
                darkest = Math.min(darkest, brightness);
                lightest = Math.max(lightest, brightness);
            }
        }
        assertTrue(lightest - darkest > 20, "MCP generated image looks blank");
        Path images = Path.of("target", "integration-images");
        java.nio.file.Files.createDirectories(images);
        java.nio.file.Files.write(images.resolve("mcp-sd15-lcm.png"), png);
        System.out.println("MCP result: " + metadata);
        onFx(() -> {
            ((ToggleButton) root.lookup("#mcpToggle")).fire();
            assertFalse(root.lookup("#mcpSettingsButton").isDisabled());
            return null;
        });
        assertFalse(server.isRunning());
    }

    private static void assertThrowsCancellation(CompletableFuture<?> future) {
    org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.CancellationException.class,
            () -> future.get(10, TimeUnit.SECONDS));
    }
}
