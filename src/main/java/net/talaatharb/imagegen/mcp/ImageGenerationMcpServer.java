package net.talaatharb.imagegen.mcp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import net.talaatharb.imagegen.config.AppSettings;
import net.talaatharb.imagegen.service.GeneratedImage;
import net.talaatharb.imagegen.service.GenerationDefaults;
import net.talaatharb.imagegen.service.ImageGenerationRequest;
import net.talaatharb.imagegen.service.ImageGenerationService;
import net.talaatharb.imagegen.service.PreviewListener;
import net.talaatharb.imagegen.service.ImageEditingRequest;
import net.talaatharb.imagegen.service.ImageEditingService;
import net.talaatharb.imagegen.service.ImageUpscalingService;
import net.talaatharb.imagegen.image.ImageInputs;
import java.awt.image.BufferedImage;
import net.talaatharb.imagegen.ui.TaskRunner;

/**
 * Stateless MCP Streamable HTTP transport: one JSON response per POST, no SSE or sessions.
 * Uses the JDK HTTP server so the desktop application needs no servlet container.
 */
@Singleton
public final class ImageGenerationMcpServer implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ImageGenerationMcpServer.class);
    private static final Set<String> VERSIONS = Set.of("2025-03-26", "2025-06-18", "2025-11-25");
    private static final int MAX_BODY = 64 * 1024;
    private static final int MAX_IMAGE_BODY = 24 * 1024 * 1024;
    private final ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final AppSettings settings;
    private final List<ImageGenerationService> models;
    private final TaskRunner runner;
    private final List<ImageEditingService> editors;
    private final List<ImageUpscalingService> upscalers;
    private HttpServer server;
    private ExecutorService executor;
    private String defaultModel;

    @Inject
    public ImageGenerationMcpServer(AppSettings settings, List<ImageGenerationService> models, TaskRunner runner,
            List<ImageEditingService> editors, List<ImageUpscalingService> upscalers) {
        this.settings = settings;
        this.models = models;
        this.runner = runner;
        this.editors = editors;
        this.upscalers = upscalers;
    }

    public synchronized boolean isRunning() {
        return server != null;
    }

    public synchronized String endpoint() {
        return server == null ? "" : "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
    }

    public synchronized void start() throws IOException {
        if (server != null) {
            return;
        }
        int port = settings.mcpPort();
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("MCP port must be between 1 and 65535");
        }
        defaultModel = model(settings.mcpModel()).id();
        HttpServer created = HttpServer.create(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 8);
        executor = Executors.newFixedThreadPool(4, r -> {
            Thread thread = new Thread(r, "image-gen-mcp");
            thread.setDaemon(true);
            return thread;
        });
        created.setExecutor(executor);
        created.createContext("/mcp", this::handle);
        created.start();
        server = created;
        LOG.info("MCP server listening at {}", endpoint());
    }

    @Override
    public synchronized void close() {
        if (server != null) {
            server.stop(0);
            server = null;
            executor.shutdownNow();
            executor = null;
            LOG.info("MCP server stopped");
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            int port = exchange.getLocalAddress().getPort();
            String host = exchange.getRequestHeaders().getFirst("Host");
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            if (!Set.of("127.0.0.1:" + port, "localhost:" + port).contains(host == null ? "" : host)
                    || (origin != null && !Set.of("http://127.0.0.1:" + port, "http://localhost:" + port)
                            .contains(origin))) {
                send(exchange, 403, Map.of("error", "Only localhost requests with a local Origin are allowed"));
                return;
            }
            if (!"/mcp".equals(exchange.getRequestURI().getPath())) {
                send(exchange, 404, Map.of("error", "Unknown endpoint"));
                return;
            }
            String version = exchange.getRequestHeaders().getFirst("MCP-Protocol-Version");
            if (version != null && !VERSIONS.contains(version)) {
                send(exchange, 400, Map.of("error", "Unsupported MCP protocol version"));
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST");
                send(exchange, 405, Map.of("error", "This stateless endpoint does not offer an SSE stream"));
                return;
            }
            String type = exchange.getRequestHeaders().getFirst("Content-Type");
            if (type == null || !"application/json".equalsIgnoreCase(type.split(";", 2)[0].trim())) {
                send(exchange, 415, Map.of("error", "Content-Type must be application/json"));
                return;
            }
            String accept = exchange.getRequestHeaders().getFirst("Accept");
            if (accept == null || !accept.contains("application/json") || !accept.contains("text/event-stream")) {
                send(exchange, 406, Map.of("error", "Accept must include application/json and text/event-stream"));
                return;
            }
            byte[] body = exchange.getRequestBody().readNBytes(MAX_IMAGE_BODY + 1);
            if (body.length > MAX_IMAGE_BODY) {
                send(exchange, 413, Map.of("error", "Request exceeds 24 MiB"));
                return;
            }
            JsonNode request;
            try {
                request = json.readTree(body);
            } catch (JsonProcessingException e) {
                send(exchange, 400, error(null, -32700, "Invalid JSON"));
                return;
            }
            if (request == null || !request.isObject() || !"2.0".equals(request.path("jsonrpc").asText())
                    || !request.path("method").isTextual()
                    || (request.has("id") && !request.get("id").isTextual() && !request.get("id").isIntegralNumber())
                    || (request.has("params") && !request.get("params").isObject())) {
                send(exchange, 400, error(null, -32600, "Invalid JSON-RPC request"));
                return;
            }
            JsonNode id = request.get("id");
            boolean imageTool = "tools/call".equals(request.path("method").asText())
                    && Set.of("edit_image", "upscale_image").contains(request.path("params").path("name").asText());
            if (!imageTool && body.length > MAX_BODY) {
                send(exchange, 413, Map.of("error", "Non-image requests must fit within 64 KiB"));
                return;
            }
            if (id == null) {
                if (!"notifications/initialized".equals(request.path("method").asText())) {
                    send(exchange, 400, error(null, -32601, "Unsupported notification"));
                    return;
                }
                exchange.sendResponseHeaders(202, -1);
                return;
            }
            try {
                Object result = dispatch(request.path("method").asText(), request.path("params"));
                ObjectNode response = json.createObjectNode().put("jsonrpc", "2.0");
                response.set("id", id);
                response.set("result", json.valueToTree(result));
                send(exchange, 200, response);
            } catch (UnknownMethodException e) {
                send(exchange, 200, error(id, -32601, e.getMessage()));
            } catch (IllegalArgumentException e) {
                send(exchange, 200, error(id, -32602, e.getMessage()));
            } catch (IOException e) {
                LOG.error("MCP request failed", e);
                send(exchange, 200, error(id, -32603, "Could not encode generated image"));
            }
        } catch (IOException e) {
            LOG.warn("MCP connection failed", e);
            throw e;
        }
    }

    private Object dispatch(String method, JsonNode params) throws IOException {
        return switch (method) {
            case "initialize" -> Map.of(
                    "protocolVersion", VERSIONS.contains(params.path("protocolVersion").asText())
                            ? params.path("protocolVersion").asText() : "2025-11-25",
                    "capabilities", Map.of("tools", Map.of("listChanged", false)),
                    "serverInfo", Map.of("name", "image-gen", "version", "1.0.0"),
                    "instructions", "Download models in the app first. Only one UI or MCP job can run at a time.");
            case "ping" -> Map.of();
            case "tools/list" -> Map.of("tools", List.of(
                    Map.of("name", "list_models", "description", "List models, installation status and defaults",
                            "inputSchema", Map.of("type", "object", "properties", Map.of(), "additionalProperties", false)),
                    Map.of("name", "generate_image", "description",
                            "Generate a PNG locally. Default model: " + defaultModel
                                    + ". The model must already be downloaded in the app.",
                            "inputSchema", generationSchema()),
                    Map.of("name", "edit_image", "description",
                            "Edit a base64 PNG/JPEG: img2img, inpaint (white mask edits), or instruction (FLUX.2 Klein). "
                                    + "Download models in the app first; see list_models for compatible modes.",
                            "inputSchema", editingSchema()),
                    Map.of("name", "upscale_image", "description",
                            "Upscale a base64 PNG/JPEG 4x with Real-ESRGAN. Download the model in the app first.",
                            "inputSchema", Map.of("type", "object", "required", List.of("image"),
                                    "properties", Map.of("image", imageSchema(), "model",
                                            Map.of("type", List.of("string", "null"), "default", settings.mcpUpscalingModel()),
                                            "tile_size", optionalNumber("Tile size: 32-256 in multiples of 32", true))))));
            case "tools/call" -> call(params);
            default -> throw new UnknownMethodException("Unknown method: " + method);
        };
    }

    private Object generationSchema() {
        return Map.of("type", "object", "required", List.of("prompt"), "additionalProperties", false,
                "properties", Map.of(
                        "prompt", Map.of("type", "string", "minLength", 1, "maxLength", 8192),
                        "model", Map.of("type", List.of("string", "null"), "default", defaultModel,
                                "description", "Model ID or name; empty, null or 'default' uses the configured default"),
                        "negative_prompt", Map.of("type", List.of("string", "null")),
                        "width", optionalNumber("A side length listed by list_models", true),
                        "height", optionalNumber("A side length listed by list_models", true),
                        "steps", optionalNumber("Denoising steps within the model limit", true),
                        "guidance_scale", optionalNumber("Guidance 0-20 (if supported)", false),
                        "seed", optionalNumber("Integer >= -1; default is random", true)));
    }

    private static Object optionalNumber(String description, boolean integer) {
        return Map.of("type", List.of(integer ? "integer" : "number", "string", "null"),
                "description", description + ". Empty, null, 'default' or invalid values use model defaults.");
    }

    private static Object imageSchema() {
        return Map.of("type", "string", "description",
                "Base64 PNG/JPEG bytes or data:image/png;base64,... (max 8 MiB decoded file, 16 MP). Not a file path.");
    }

    private Object editingSchema() {
        Map<String, Object> properties = new java.util.LinkedHashMap<>();
        properties.put("image", imageSchema());
        properties.put("mask", imageSchema());
        properties.put("prompt", Map.of("type", "string", "minLength", 1, "maxLength", 8192));
        properties.put("mode", Map.of("type", List.of("string", "null"), "default", settings.mcpEditingMode(),
                "description", "img2img, inpaint, instruction. Unknown/empty values use default."));
        properties.put("model", Map.of("type", List.of("string", "null"), "description",
                "Optional editing model ID/name. Default depends on mode; see list_models."));
        properties.put("strength", optionalNumber("Denoising strength >0 to 1 (unused for instruction)", false));
        for (String key : List.of("width", "height", "steps", "guidance_scale", "seed")) {
            properties.put(key, optionalNumber("Uses the selected model's defaults", !key.equals("guidance_scale")));
        }
        properties.put("negative_prompt", Map.of("type", List.of("string", "null")));
        return Map.of("type", "object", "required", List.of("image", "prompt"), "properties", properties);
    }

    private Object call(JsonNode params) throws IOException {
        String name = text(params, "name", null);
        if ("generate_image".equals(name)) {
            LOG.info("Received MCP generate_image call");
        }
        JsonNode args = params.path("arguments");
        if (useDefault(args)) {
            args = json.createObjectNode();
        }
        if ("list_models".equals(name)) {
            return textResult(json.writeValueAsString(Map.of("default_model", defaultModel, "models",
                    models.stream().map(m -> Map.of("id", m.id(), "name", m.displayName(),
                            "installed", m.isInstalled(), "defaults", m.defaults())).toList(),
                    "editing", editors.stream().map(m -> Map.of("id", m.id(), "name", m.displayName(),
                            "installed", m.isInstalled(), "defaults", m.defaults(), "modes",
                            java.util.Arrays.stream(ImageEditingRequest.Mode.values()).filter(m::supports)
                                    .map(ImageEditingRequest.Mode::id).toList())).toList(),
                    "upscaling", upscalers.stream().map(m -> Map.of("id", m.id(), "name", m.displayName(),
                            "installed", m.isInstalled(), "scale", m.scale())).toList(),
                    "task_defaults", Map.of("edit_model", settings.mcpEditingModel(),
                            "instruction_model", settings.mcpInstructionModel(), "edit_mode", settings.mcpEditingMode(),
                            "strength", settings.mcpEditingStrength(), "upscale_model", settings.mcpUpscalingModel(),
                            "tile_size", settings.mcpUpscalingTileSize()))), false);
        }
        if (!args.isObject()) {
            throw new IllegalArgumentException("arguments must be an object containing a prompt");
        }
        if ("edit_image".equals(name)) {
            return edit(args);
        }
        if ("upscale_image".equals(name)) {
            return upscale(args);
        }
        if (!"generate_image".equals(name)) {
            throw new IllegalArgumentException("Unknown tool: " + name);
        }
        Set<String> allowed = Set.of("prompt", "model", "negative_prompt", "width", "height", "steps",
                "guidance_scale", "seed");
        List<String> warnings = new ArrayList<>();
        args.fieldNames().forEachRemaining(key -> {
            if (!allowed.contains(key)) {
                warnings.add("Ignored unknown argument: " + key);
            }
        });
        String chosen = optionalText(args, "model", defaultModel, warnings).trim();
        ImageGenerationService service = models.stream()
                .filter(m -> m.id().equalsIgnoreCase(chosen) || m.displayName().equalsIgnoreCase(chosen))
                .findFirst().orElseGet(() -> {
                    warnings.add("Unknown model '" + chosen + "'; using " + defaultModel);
                    return model(defaultModel);
                });
        GenerationDefaults d = service.defaults();
        ImageGenerationRequest request = generationRequest(args, d, warnings);
        LOG.info("MCP generate_image requested: model={}, size={}x{}, steps={}, guidance={}, seed={}, corrections={}",
                service.id(), request.width(), request.height(), request.steps(), request.guidanceScale(),
                request.seed(), warnings.size());
        CompletableFuture<GeneratedImage> job = runner.submitRemote("MCP: " + service.displayName(), progress -> {
            requireInstalled(service);
            return service.generate(request, progress, PreviewListener.NONE);
        });
        return awaitImage(job, "generate_image", warnings);
    }

    private ImageGenerationRequest generationRequest(JsonNode args, GenerationDefaults d, List<String> warnings) {
        int width = (int) optionalNumber(args, "width", d.width(),
                n -> d.sizes().stream().anyMatch(size -> n.compareTo(java.math.BigDecimal.valueOf(size)) == 0), warnings);
        int height = (int) optionalNumber(args, "height", d.height(),
                n -> d.sizes().stream().anyMatch(size -> n.compareTo(java.math.BigDecimal.valueOf(size)) == 0), warnings);
        int steps = (int) optionalNumber(args, "steps", d.steps(),
                n -> isInteger(n) && n.signum() > 0 && n.compareTo(java.math.BigDecimal.valueOf(d.maxSteps())) <= 0, warnings);
        float guidance = (float) optionalNumber(args, "guidance_scale", d.guidanceScale(),
                n -> n.signum() >= 0 && n.compareTo(java.math.BigDecimal.valueOf(20)) <= 0, warnings);
        // Parse seeds separately to preserve every bit of a 64-bit integer.
        long seed = optionalSeed(args, warnings);
        String prompt = text(args, "prompt", null);
        String negative = optionalText(args, "negative_prompt", "", warnings);
        if (prompt.length() > 8192) {
            throw new IllegalArgumentException("Prompt must be at most 8192 characters");
        }
        if (negative.length() > 8192 || (!d.supportsNegativePrompt() && !negative.isBlank())) {
            warnings.add("Ignored unsupported or oversized negative_prompt");
            negative = "";
        }
        if (!d.supportsGuidance() && guidance != d.guidanceScale()) {
            warnings.add("Ignored custom guidance_scale for this model");
            guidance = d.guidanceScale();
        }
        return new ImageGenerationRequest(prompt, negative, width, height, steps, guidance, seed);
    }

    private Object awaitImage(CompletableFuture<GeneratedImage> job, String tool, List<String> warnings) throws IOException {
        try {
            GeneratedImage image = job.get();
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            if (!ImageIO.write(image.image(), "png", png)) {
                throw new IOException("No PNG encoder available");
            }
            LOG.info("MCP {} completed: model={}, size={}x{}, seed={}, elapsedMs={}, pngBytes={}",
                    tool, image.modelId(), image.image().getWidth(), image.image().getHeight(), image.request().seed(),
                    image.elapsed().toMillis(), png.size());
            return Map.of("isError", false, "content", List.of(
                    Map.of("type", "text", "text", json.writeValueAsString(Map.of(
                            "model", image.modelId(), "seed", image.request().seed(),
                            "width", image.image().getWidth(), "height", image.image().getHeight(),
                            "elapsed_ms", image.elapsed().toMillis(), "warnings", warnings))),
                    Map.of("type", "image", "mimeType", "image/png",
                            "data", Base64.getEncoder().encodeToString(png.toByteArray()))));
        } catch (InterruptedException e) {
            job.cancel(true);
            Thread.currentThread().interrupt();
            LOG.info("MCP {} cancelled because the server stopped", tool);
            return textResult("MCP server stopped; generation cancelled", true);
        } catch (CancellationException e) {
            LOG.info("MCP {} cancelled", tool);
            return textResult("Generation cancelled", true);
        } catch (ExecutionException e) {
            LOG.warn("MCP generation failed", e.getCause());
            return textResult(e.getCause().getMessage() == null ? "Generation failed" : e.getCause().getMessage(), true);
        }

    }

    private static void requireInstalled(net.talaatharb.imagegen.service.ImageModelService service) {
        if (!service.isInstalled()) {
            throw new IllegalStateException("Download " + service.displayName() + " in the app before using MCP");
        }
    }

    private BufferedImage inputImage(JsonNode args, String key) {
        try {
            return ImageInputs.base64(text(args, key, null));
        } catch (IOException e) {
            throw new IllegalArgumentException(key + " is not a readable PNG/JPEG", e);
        }
    }

    private Object edit(JsonNode args) throws IOException {
        LOG.info("Received MCP edit_image call");
        List<String> warnings = new ArrayList<>();
        ignoreExtra(args, Set.of("image", "mask", "mode", "model", "prompt", "negative_prompt",
                "width", "height", "steps", "guidance_scale", "seed", "strength"), warnings);
        String requestedMode = optionalText(args, "mode", settings.mcpEditingMode(), warnings);
        ImageEditingRequest.Mode mode = java.util.Arrays.stream(ImageEditingRequest.Mode.values())
                .filter(m -> m.id().equalsIgnoreCase(requestedMode.trim())).findFirst().orElseGet(() -> {
                    warnings.add("Unknown mode; using " + settings.mcpEditingMode());
                    return java.util.Arrays.stream(ImageEditingRequest.Mode.values())
                            .filter(m -> m.id().equals(settings.mcpEditingMode())).findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("Invalid configured editing mode"));
                });
        String fallback = mode == ImageEditingRequest.Mode.INSTRUCTION
                ? settings.mcpInstructionModel() : settings.mcpEditingModel();
        List<ImageEditingService> compatible = editors.stream().filter(m -> m.supports(mode)).toList();
        ImageEditingService service = chooseModel(args, compatible, fallback, warnings);
        BufferedImage image = inputImage(args, "image");
        BufferedImage mask = mode == ImageEditingRequest.Mode.INPAINT ? inputImage(args, "mask") : null;
        if (mode != ImageEditingRequest.Mode.INPAINT && !useDefault(args.get("mask"))) {
            warnings.add("Ignored mask; mode is not inpaint");
        }
        if (mode == ImageEditingRequest.Mode.INSTRUCTION && !useDefault(args.get("strength"))) {
            warnings.add("Ignored strength; instruction mode uses a reference image");
        }
        float strength = (float) optionalNumber(args, "strength", settings.mcpEditingStrength(),
                n -> n.signum() > 0 && n.compareTo(java.math.BigDecimal.ONE) <= 0, warnings);
        var request = new ImageEditingRequest(image, mask, mode, generationRequest(args, service.defaults(), warnings), strength);
        LOG.info("MCP edit_image requested: model={}, mode={}, input={}x{}, corrections={}",
                service.id(), mode.id(), image.getWidth(), image.getHeight(), warnings.size());
        return awaitImage(runner.submitRemote("MCP editing: " + service.displayName(), progress -> {
            requireInstalled(service);
            return service.edit(request, progress, PreviewListener.NONE);
        }), "edit_image", warnings);
    }

    private Object upscale(JsonNode args) throws IOException {
        LOG.info("Received MCP upscale_image call");
        List<String> warnings = new ArrayList<>();
        ignoreExtra(args, Set.of("image", "model", "tile_size"), warnings);
        ImageUpscalingService service = chooseModel(args, upscalers, settings.mcpUpscalingModel(), warnings);
        BufferedImage image = inputImage(args, "image");
        if ((long) image.getWidth() * image.getHeight() * service.scale() * service.scale() > ImageInputs.MAX_PIXELS) {
            throw new IllegalArgumentException("Upscaled output exceeds the 16 megapixel limit");
        }
        int tile = (int) optionalNumber(args, "tile_size", settings.mcpUpscalingTileSize(),
                n -> isInteger(n) && n.intValue() >= 32 && n.intValue() <= 256
                        && n.compareTo(java.math.BigDecimal.valueOf(n.intValue())) == 0 && n.intValue() % 32 == 0, warnings);
        LOG.info("MCP upscale_image requested: model={}, input={}x{}, tile={}, corrections={}",
                service.id(), image.getWidth(), image.getHeight(), tile, warnings.size());
        var job = runner.submitRemote("MCP upscaling: " + service.displayName(), progress -> {
            requireInstalled(service);
            return service.upscale(image, tile, progress);
        });
        try {
            var result = job.get();
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            if (!ImageIO.write(result.image(), "png", png)) {
                throw new IOException("No PNG encoder available");
            }
            LOG.info("MCP upscale_image completed: model={}, size={}x{}, elapsedMs={}", result.modelId(),
                    result.image().getWidth(), result.image().getHeight(), result.elapsed().toMillis());
            return Map.of("isError", false, "content", List.of(
                    Map.of("type", "text", "text", json.writeValueAsString(Map.of("model", result.modelId(),
                            "width", result.image().getWidth(), "height", result.image().getHeight(),
                            "scale", result.scale(), "elapsed_ms", result.elapsed().toMillis(), "warnings", warnings))),
                    Map.of("type", "image", "mimeType", "image/png", "data", Base64.getEncoder().encodeToString(png.toByteArray()))));
        } catch (InterruptedException e) {
            job.cancel(true);
            Thread.currentThread().interrupt();
            LOG.info("MCP upscale_image cancelled because the server stopped");
            return textResult("Upscaling cancelled", true);
        } catch (CancellationException e) {
            LOG.info("MCP upscale_image cancelled");
            return textResult("Upscaling cancelled", true);
        } catch (ExecutionException e) {
            LOG.warn("MCP upscaling failed", e.getCause());
            return textResult(e.getCause().getMessage() == null ? "Upscaling failed" : e.getCause().getMessage(), true);
        }
    }

    private static void ignoreExtra(JsonNode args, Set<String> allowed, List<String> warnings) {
        args.fieldNames().forEachRemaining(key -> {
            if (!allowed.contains(key)) { warnings.add("Ignored unknown argument: " + key); }
        });
    }

    private static <T extends net.talaatharb.imagegen.service.ImageModelService> T chooseModel(
            JsonNode args, List<T> choices, String fallback, List<String> warnings) {
        String selected = optionalText(args, "model", fallback, warnings).trim();
        return choices.stream().filter(m -> m.id().equalsIgnoreCase(selected) || m.displayName().equalsIgnoreCase(selected))
                .findFirst().orElseGet(() -> {
                    warnings.add("Unknown or incompatible model '" + selected + "'; using " + fallback);
                    return choices.stream().filter(m -> m.id().equals(fallback)).findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("Invalid configured default model: " + fallback));
                });
    }

    private ImageGenerationService model(String id) {
        return models.stream().filter(m -> m.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown model: " + id));
    }

    private static String text(JsonNode node, String key, String fallback) {
        JsonNode value = node.get(key);
        if (value == null && fallback != null) {
            return fallback;
        }
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException(key + " must be a string");
        }
        return value.textValue();
    }

    private static boolean useDefault(JsonNode value) {
        return value == null || value.isMissingNode() || value.isNull()
                || (value.isTextual() && (value.textValue().isBlank()
                        || "default".equalsIgnoreCase(value.textValue().trim())));
    }

    private static String optionalText(JsonNode args, String key, String fallback, List<String> warnings) {
        JsonNode value = args.get(key);
        if (useDefault(value)) {
            return fallback;
        }
        if (!value.isTextual()) {
            warnings.add("Invalid " + key + "; using default");
            return fallback;
        }
        return value.textValue();
    }

    private static boolean isInteger(java.math.BigDecimal number) {
        return number.stripTrailingZeros().scale() <= 0;
    }

    private static java.math.BigDecimal number(JsonNode value) {
        if (!value.isNumber() && !value.isTextual()) {
            throw new NumberFormatException("Not a number");
        }
        return new java.math.BigDecimal(value.asText().trim());
    }

    private static double optionalNumber(JsonNode args, String key, double fallback,
            java.util.function.Predicate<java.math.BigDecimal> valid, List<String> warnings) {
        JsonNode value = args.get(key);
        if (useDefault(value)) {
            return fallback;
        }
        try {
            java.math.BigDecimal parsed = number(value);
            if (valid.test(parsed)) {
                return parsed.doubleValue();
            }
        } catch (NumberFormatException e) {
            // Malformed optional values are recoverable; report the fallback in the tool result.
        }
        warnings.add("Invalid " + key + "; using default " + fallback);
        return fallback;
    }

    private static long optionalSeed(JsonNode args, List<String> warnings) {
        JsonNode value = args.get("seed");
        if (useDefault(value)) {
            return -1;
        }
        try {
            long seed = number(value).longValueExact();
            if (seed >= -1) {
                return seed;
            }
        } catch (ArithmeticException | NumberFormatException e) {
            // Invalid optional seed means random, not a failed generation.
        }
        warnings.add("Invalid seed; using random");
        return -1;
    }

    private static Object textResult(String text, boolean error) {
        return Map.of("isError", error, "content", List.of(Map.of("type", "text", "text", text)));
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode result = json.createObjectNode().put("jsonrpc", "2.0");
        result.set("id", id == null ? json.nullNode() : id);
        result.set("error", json.valueToTree(Map.of("code", code, "message", message)));
        return result;
    }

    private void send(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] bytes = json.writeValueAsBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static final class UnknownMethodException extends IllegalArgumentException {
        UnknownMethodException(String message) {
            super(message);
        }
    }
}
