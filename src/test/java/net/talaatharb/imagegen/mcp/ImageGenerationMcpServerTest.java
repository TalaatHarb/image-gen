package net.talaatharb.imagegen.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.talaatharb.imagegen.config.AppSettings;
import net.talaatharb.imagegen.model.ProgressListener;
import net.talaatharb.imagegen.service.GeneratedImage;
import net.talaatharb.imagegen.service.GenerationDefaults;
import net.talaatharb.imagegen.service.ImageGenerationRequest;
import net.talaatharb.imagegen.service.ImageGenerationService;
import net.talaatharb.imagegen.service.PreviewListener;
import net.talaatharb.imagegen.ui.TaskRunner;

class ImageGenerationMcpServerTest {
    @TempDir Path dir;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();
    private final FakeModel lcm = new FakeModel("sd15-lcm");
    private final FakeModel other = new FakeModel("other");
    private final FakeEditor editor = new FakeEditor();
    private final FakeUpscaler upscaler = new FakeUpscaler();
    private AppSettings settings;
    private ImageGenerationMcpServer server;
    private CompletableFuture<?> pending;
    private CountDownLatch pendingStarted;
    private final TaskRunner runner = new TaskRunner() {
        @Override
        public <T> CompletableFuture<T> submitRemote(String title, Work<T> work) {
            if (pendingStarted != null) {
                CompletableFuture<T> future = new CompletableFuture<>();
                pending = future;
                pendingStarted.countDown();
                return future;
            }
            try {
                return CompletableFuture.completedFuture(work.run(ProgressListener.NONE));
            } catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
        }
    };

    @BeforeEach
    void start() throws Exception {
        settings = new AppSettings(dir.resolve("settings.properties"));
        assertEquals("sd15-lcm", settings.mcpModel());
        assertEquals(8764, settings.mcpPort());
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            settings.setMcpConfiguration(socket.getLocalPort(), settings.mcpModel());
        }
        server = new ImageGenerationMcpServer(settings, List.of(lcm, other), runner, List.of(editor), List.of(upscaler));
        assertFalse(server.isRunning());
        server.start();
    }

    @AfterEach
    void stop() {
        server.close();
        runner.shutdown();
    }

    private HttpRequest.Builder post(String body) {
        return HttpRequest.newBuilder(URI.create(server.endpoint()))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode rpc(String method, String params) throws Exception {
        HttpResponse<String> response = send(post("""
                {"jsonrpc":"2.0","id":"test","method":"%s","params":%s}
                """.formatted(method, params)));
        assertEquals(200, response.statusCode(), response.body());
        JsonNode value = json.readTree(response.body());
        assertEquals("test", value.path("id").asText());
        return value;
    }

    @Test
    void initializesAndListsToolsAndModels() throws Exception {
        assertEquals("2025-06-18", rpc("initialize", """
                {"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1"}}
                """).path("result").path("protocolVersion").asText());
        assertEquals(202, send(post("""
                {"jsonrpc":"2.0","method":"notifications/initialized"}
                """)).statusCode());
        assertEquals(4, rpc("tools/list", "{}").path("result").path("tools").size());
        JsonNode listed = rpc("tools/call", """
                {"name":"list_models","arguments":{}}
                """).path("result");
        JsonNode models = json.readTree(listed.path("content").get(0).path("text").asText());
        assertEquals("sd15-lcm", models.path("default_model").asText());
        assertEquals(2, models.path("models").size());
    }

    @Test
    void generatesPngWithModelDefaultsAndMetadata() throws Exception {
        JsonNode result = rpc("tools/call", """
                {"name":"generate_image","arguments":{"prompt":"a fox","seed":42}}
                """).path("result");
        assertFalse(result.path("isError").asBoolean());
        JsonNode content = result.path("content");
        JsonNode metadata = json.readTree(content.get(0).path("text").asText());
        assertEquals("sd15-lcm", metadata.path("model").asText());
        assertEquals(42, metadata.path("seed").asLong());
        assertEquals("image/png", content.get(1).path("mimeType").asText());
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(
                Base64.getDecoder().decode(content.get(1).path("data").asText())));
        assertEquals(64, image.getWidth());
        assertEquals(4, lcm.last.steps());
        assertEquals(1, lcm.last.guidanceScale());
    }

    @Test
    void persistsConfigurationAndAllowsModelOverride() throws Exception {
        server.close();
        settings.setMcpConfiguration(settings.mcpPort(), "other");
        AppSettings reloaded = new AppSettings(dir.resolve("settings.properties"));
        assertEquals("other", reloaded.mcpModel());
        server = new ImageGenerationMcpServer(reloaded, List.of(lcm, other), runner, List.of(editor), List.of(upscaler));
        assertFalse(server.isRunning());
        server.start();
        rpc("tools/call", """
                {"name":"generate_image","arguments":{"prompt":"default"}}
                """);
        assertEquals("default", other.last.prompt());
        rpc("tools/call", """
                {"name":"generate_image","arguments":{"prompt":"override","model":"sd15-lcm"}}
                """);
        assertEquals("override", lcm.last.prompt());
    }

    @Test
    void rejectsEssentialInputAndReportsMissingModelsWithoutDownloading() throws Exception {
        for (String args : List.of("{\"prompt\":\" \"}", "{}", "{\"prompt\":null}", "{\"prompt\":42}")) {
            assertEquals(-32602, rpc("tools/call", "{\"name\":\"generate_image\",\"arguments\":" + args + "}")
                    .path("error").path("code").asInt(), args);
        }
        lcm.installed = false;
        JsonNode result = rpc("tools/call", """
                {"name":"generate_image","arguments":{"prompt":"fox"}}
                """).path("result");
        assertTrue(result.path("isError").asBoolean());
        assertTrue(result.path("content").get(0).path("text").asText().contains("Download"));
        assertNull(lcm.last);
    }

    @Test
    void defaultsEmptyNullAndDefaultOptionalArguments() throws Exception {
        for (String value : List.of("\"\"", "\"  \"", "\" DeFaUlT \"", "null")) {
            JsonNode result = rpc("tools/call", """
                    {"name":"generate_image","arguments":{"prompt":"fox","model":%s,"width":%s,"height":%s,
                     "steps":%s,"guidance_scale":%s,"seed":%s,"negative_prompt":%s}}
                    """.formatted(value, value, value, value, value, value, value)).path("result");
            assertFalse(result.path("isError").asBoolean(), result.toString());
            assertEquals(new ImageGenerationRequest("fox", "", 64, 64, 4, 1, -1), lcm.last);
        }
        for (String value : List.of("\"\"", "\"default\"", "null")) {
            assertFalse(rpc("tools/call", "{\"name\":\"list_models\",\"arguments\":" + value + "}")
                    .path("result").path("isError").asBoolean());
        }
    }

    @Test
    void correctsMalformedOptionalsAndReportsCorrections() throws Exception {
        JsonNode result = rpc("tools/call", """
                {"name":"generate_image","arguments":{"prompt":"fox","model":"missing","width":999999,
                 "height":{},"steps":999,"guidance_scale":2,"seed":1.5,"negative_prompt":"bad","extra":true}}
                """).path("result");
        assertFalse(result.path("isError").asBoolean());
        assertEquals(new ImageGenerationRequest("fox", "", 64, 64, 4, 1, -1), lcm.last);
        JsonNode metadata = json.readTree(result.path("content").get(0).path("text").asText());
        assertEquals(8, metadata.path("warnings").size());
    }

    @Test
    void acceptsNumericStringsAndPreservesLargeSeed() throws Exception {
        JsonNode result = rpc("tools/call", """
                {"name":"generate_image","arguments":{"prompt":"fox","model":"SD15-LCM","width":"128",
                 "height":"64","steps":"6","guidance_scale":"1.0","seed":"9223372036854775807"}}
                """).path("result");
        assertFalse(result.path("isError").asBoolean());
        assertEquals(new ImageGenerationRequest("fox", "", 128, 64, 6, 1, Long.MAX_VALUE), lcm.last);
    }

    @Test
    void checksOriginAndHttpTransport() throws Exception {
        String ping = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";
        assertEquals(403, send(post(ping).header("Origin", "https://evil.example")).statusCode());
        assertEquals(403, send(post(ping).header("Origin", "null")).statusCode());
        assertEquals(200, send(post(ping).header("Origin",
                "http://127.0.0.1:" + settings.mcpPort())).statusCode());
        assertEquals(400, send(post(ping).header("MCP-Protocol-Version", "invalid")).statusCode());
        assertEquals(406, send(post(ping).setHeader("Accept", "application/json")).statusCode());
        assertEquals(415, send(post(ping).setHeader("Content-Type", "text/plain")).statusCode());
        assertEquals(413, send(post(ping.replace("\"method\":\"ping\"", "\"method\":\"ping\",\"padding\":\""
                + "x".repeat(65537) + "\""))).statusCode());
        assertEquals(405, send(HttpRequest.newBuilder(URI.create(server.endpoint())).GET()).statusCode());
        assertEquals(-32700, json.readTree(send(post("{")).body()).path("error").path("code").asInt());
        assertEquals(-32700, json.readTree(send(post(ping + "{}")).body()).path("error").path("code").asInt());
        assertEquals(-32600, json.readTree(send(post("[]")).body()).path("error").path("code").asInt());
        assertEquals(-32601, rpc("unknown", "{}").path("error").path("code").asInt());
    }

    @Test
    void stopsReleasesPortAndRejectsUnknownDefaultModel() throws Exception {
        server.close();
        assertFalse(server.isRunning());
        try (ServerSocket socket = new ServerSocket(settings.mcpPort(), 1, InetAddress.getByName("127.0.0.1"))) {
            assertEquals(settings.mcpPort(), socket.getLocalPort());
        }
        settings.setMcpConfiguration(settings.mcpPort(), "missing");
        assertThrows(IllegalArgumentException.class, server::start);
        assertFalse(server.isRunning());
    }

    @Test
    void stoppingCancelsActiveRemoteWork() throws Exception {
        pendingStarted = new CountDownLatch(1);
        CompletableFuture<HttpResponse<String>> response = client.sendAsync(post("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call",
                 "params":{"name":"generate_image","arguments":{"prompt":"fox"}}}
                """).build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(pendingStarted.await(10, TimeUnit.SECONDS));
        server.close();
        try {
            response.get(10, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            assertInstanceOf(java.io.IOException.class, e.getCause());
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!pending.isCancelled() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(pending.isCancelled());
        assertFalse(server.isRunning());
    }

    @Test
    void editsAndUpscalesBase64InputsWithResilientDefaults() throws Exception {
        String image = encodedImage(64, 64);
        JsonNode edited = rpc("tools/call", """
                {"name":"edit_image","arguments":{"image":"%s","prompt":"fox","model":"default","mode":"",
                 "strength":"default","width":"64","steps":null,"guidance_scale":"invalid"}}
                """.formatted(image)).path("result");
        assertFalse(edited.path("isError").asBoolean(), edited.toString());
        assertEquals(net.talaatharb.imagegen.service.ImageEditingRequest.Mode.IMG2IMG, editor.last.mode());
        assertEquals(0.75f, editor.last.strength());
        assertEquals(4, editor.last.generation().steps());
        JsonNode upscaled = rpc("tools/call", """
                {"name":"upscale_image","arguments":{"image":"data:image/png;base64,%s","model":null,"tile_size":"default"}}
                """.formatted(image)).path("result");
        assertFalse(upscaled.path("isError").asBoolean(), upscaled.toString());
        assertEquals(128, upscaler.tile);
        assertEquals(256, json.readTree(upscaled.path("content").get(0).path("text").asText()).path("width").asInt());
        assertEquals("image/png", upscaled.path("content").get(1).path("mimeType").asText());
    }

    @Test
    void requiresInputAndMaskButDoesNotFailOnOptionalMistakes() throws Exception {
        String image = encodedImage(64, 64);
        for (String arguments : List.of("{\"prompt\":\"fox\"}", "{\"image\":\"invalid\",\"prompt\":\"fox\"}",
                "{\"image\":\"" + image + "\",\"prompt\":\"fox\",\"mode\":\"inpaint\"}",
                "{\"image\":\"" + image + "\",\"prompt\":\"fox\",\"mode\":\"inpaint\",\"mask\":\""
                        + encodedImage(32, 32) + "\"}")) {
            assertEquals(-32602, rpc("tools/call", "{\"name\":\"edit_image\",\"arguments\":" + arguments + "}")
                    .path("error").path("code").asInt());
        }
        assertEquals(-32602, rpc("tools/call", "{\"name\":\"upscale_image\",\"arguments\":{}}")
                .path("error").path("code").asInt());
        JsonNode result = rpc("tools/call", """
                {"name":"edit_image","arguments":{"image":"%s","mask":"%s","prompt":"fox","mode":"inpaint",
                 "model":"unknown","strength":999,"extra":true}}
                """.formatted(image, image)).path("result");
        assertFalse(result.path("isError").asBoolean(), result.toString());
        assertEquals(net.talaatharb.imagegen.service.ImageEditingRequest.Mode.INPAINT, editor.last.mode());
        assertEquals(0.75f, editor.last.strength());
        assertNotNull(editor.last.mask());
    }

    private static String encodedImage(int width, int height) throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", bytes);
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    private static final class FakeEditor implements net.talaatharb.imagegen.service.ImageEditingService {
        private net.talaatharb.imagegen.service.ImageEditingRequest last;
        @Override public String id() { return "sd15"; }
        @Override public String displayName() { return id(); }
        @Override public String description() { return "test"; }
        @Override public boolean isInstalled() { return true; }
        @Override public void install(ProgressListener progress) { fail("Must not download"); }
        @Override public GenerationDefaults defaults() { return GenerationDefaults.distilled(64, 4, 12, List.of(64)); }
        @Override public boolean supports(net.talaatharb.imagegen.service.ImageEditingRequest.Mode mode) { return true; }
        @Override public GeneratedImage edit(net.talaatharb.imagegen.service.ImageEditingRequest request,
                ProgressListener progress, PreviewListener preview) {
            last = request;
            return new GeneratedImage(request.image(), id(), request.generation().withSeed(42), Duration.ofMillis(1));
        }
    }

    private static final class FakeUpscaler implements net.talaatharb.imagegen.service.ImageUpscalingService {
        private int tile;
        @Override public String id() { return "realesrgan-x4plus"; }
        @Override public String displayName() { return id(); }
        @Override public String description() { return "test"; }
        @Override public boolean isInstalled() { return true; }
        @Override public void install(ProgressListener progress) { fail("Must not download"); }
        @Override public int scale() { return 4; }
        @Override public net.talaatharb.imagegen.service.UpscaledImage upscale(BufferedImage image, int tileSize,
                ProgressListener progress) {
            tile = tileSize;
            return new net.talaatharb.imagegen.service.UpscaledImage(new BufferedImage(image.getWidth() * 4,
                    image.getHeight() * 4, BufferedImage.TYPE_INT_RGB), id(), 4, Duration.ofMillis(1));
        }
    }

    private static final class FakeModel implements ImageGenerationService {
        private final String id;
        private boolean installed = true;
        private ImageGenerationRequest last;

        FakeModel(String id) { this.id = id; }
        @Override public String id() { return id; }
        @Override public String displayName() { return id; }
        @Override public String description() { return "test"; }
        @Override public boolean isInstalled() { return installed; }
        @Override public void install(ProgressListener progress) { fail("MCP must not download models"); }
        @Override public GenerationDefaults defaults() {
            return GenerationDefaults.distilled(64, 4, 12, List.of(64, 128));
        }
        @Override public GeneratedImage generate(ImageGenerationRequest request, ProgressListener progress,
                PreviewListener preview) {
            last = request;
            return new GeneratedImage(new BufferedImage(request.width(), request.height(), BufferedImage.TYPE_INT_RGB),
                    id, request.withSeed(request.randomSeed() ? 7 : request.seed()), Duration.ofMillis(10));
        }
    }
}
