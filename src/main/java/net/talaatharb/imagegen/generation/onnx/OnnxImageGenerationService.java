package net.talaatharb.imagegen.generation.onnx;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import net.talaatharb.imagegen.image.Images;
import net.talaatharb.imagegen.model.GpuRuntime;
import net.talaatharb.imagegen.model.HuggingFaceDownloader;
import net.talaatharb.imagegen.model.HuggingFaceDownloader.RemoteFile;
import net.talaatharb.imagegen.model.ModelStore;
import net.talaatharb.imagegen.model.Onnx;
import net.talaatharb.imagegen.model.ProgressListener;
import net.talaatharb.imagegen.service.GeneratedImage;
import net.talaatharb.imagegen.service.GenerationDefaults;
import net.talaatharb.imagegen.service.ImageGenerationRequest;
import net.talaatharb.imagegen.service.ImageGenerationService;
import net.talaatharb.imagegen.service.ImageServiceException;
import net.talaatharb.imagegen.service.PreviewListener;

/**
 * Pure Java Stable Diffusion pipeline on ONNX Runtime: CLIP tokenizer, text encoder, UNet denoising loop with an
 * LCM or Euler ancestral sampler, and VAE decoder. Graph input names and types are read from the models, so standard
 * diffusers / Optimum exports work without per-model code.
 */
public class OnnxImageGenerationService implements ImageGenerationService {
    private static final Logger LOG = LoggerFactory.getLogger(OnnxImageGenerationService.class);
    private static final float VAE_SCALE = 0.18215f;
    private static final int LATENT_CHANNELS = 4;
    private static final int LATENT_FACTOR = 8;
    private static final SecureRandom SEEDS = new SecureRandom();

    private final OnnxModel model;
    private final ModelStore store;
    private final HuggingFaceDownloader downloader;

    private ClipTokenizer tokenizer;
    private SchedulerConfig schedulerConfig;
    private OrtSession textEncoder;
    private OrtSession unet;
    private OrtSession vaeDecoder;
    private boolean loadedOnGpu;

    public OnnxImageGenerationService(OnnxModel model, ModelStore store, HuggingFaceDownloader downloader) {
        this.model = model;
        this.store = store;
        this.downloader = downloader;
    }

    @Override
    public String id() {
        return model.id();
    }

    @Override
    public String displayName() {
        return model.displayName();
    }

    @Override
    public String description() {
        return model.description();
    }

    @Override
    public GenerationDefaults defaults() {
        return model.defaults();
    }

    @Override
    public long missingDownloadBytes() {
        return isInstalled() ? 0 : model.sizeBytes();
    }

    Path directory() {
        return store.directory("hf").resolve(model.repo().replace("/", "--"));
    }

    private List<RemoteFile> files() {
        Path dir = directory();
        return model.files().stream().map(f -> new RemoteFile(model.repo(), f, resolve(dir, f))).toList();
    }

    private static Path resolve(Path dir, String relative) {
        Path path = dir;
        for (String part : relative.split("/")) {
            path = path.resolve(part);
        }
        return path;
    }

    @Override
    public boolean isInstalled() {
        return HuggingFaceDownloader.allPresent(files());
    }

    @Override
    public void install(ProgressListener progress) throws IOException {
        downloader.ensure(files(), progress);
    }

    private void load(ProgressListener progress) throws IOException, OrtException {
        if (textEncoder != null && loadedOnGpu == GpuRuntime.isEnabled()) {
            return;
        }
        close();
        Path dir = directory();
        progress.update("Loading tokenizer", 0.01);
        tokenizer = ClipTokenizer.load(dir.resolve("tokenizer"));
        schedulerConfig = SchedulerConfig.load(dir.resolve("scheduler").resolve("scheduler_config.json"));
        progress.update("Loading text encoder", 0.02);
        textEncoder = Onnx.openAccelerated(dir.resolve("text_encoder").resolve("model.onnx"));
        progress.update("Loading UNet (this can take a while the first time)", 0.04);
        // Large graphs initialize much faster with extended optimizations at the same speed.
        unet = Onnx.openAccelerated(dir.resolve("unet").resolve("model.onnx"),
                OrtSession.SessionOptions.OptLevel.EXTENDED_OPT);
        progress.update("Loading VAE decoder", 0.08);
        vaeDecoder = Onnx.openAccelerated(dir.resolve("vae_decoder").resolve("model.onnx"));
        loadedOnGpu = Onnx.isAccelerated(unet);
    }

    @Override
    public synchronized GeneratedImage generate(ImageGenerationRequest request, ProgressListener progress,
            PreviewListener preview) {
        if (!isInstalled()) {
            throw new ImageServiceException(displayName() + " is not downloaded yet");
        }
        long started = System.nanoTime();
        ImageGenerationRequest seeded = request.randomSeed() ? request.withSeed(SEEDS.nextInt(Integer.MAX_VALUE))
                : request;
        try {
            load(progress);
            progress.checkCancelled();
            progress.update("Encoding prompt", 0.1);
            OrtEnvironment env = Onnx.env();
            float[] condition = encode(env, seeded.prompt());
            boolean lcm = model.sampler() == OnnxModel.Sampler.LCM;
            boolean guidance = !lcm && seeded.guidanceScale() > 1;
            float[] unconditional = guidance ? encode(env, seeded.negativePrompt()) : null;
            int hiddenTokens = ClipTokenizer.MAX_LENGTH;
            int hiddenSize = condition.length / hiddenTokens;

            Scheduler scheduler = lcm ? new LcmScheduler(schedulerConfig, seeded.steps())
                    : new EulerAncestralScheduler(schedulerConfig, seeded.steps());
            int latentWidth = seeded.width() / LATENT_FACTOR;
            int latentHeight = seeded.height() / LATENT_FACTOR;
            long[] latentShape = {1, LATENT_CHANNELS, latentHeight, latentWidth};
            Random random = new Random(seeded.seed());
            float[] latents = Scheduler.gaussian(LATENT_CHANNELS * latentHeight * latentWidth, random);
            float sigma = scheduler.initNoiseSigma();
            for (int i = 0; i < latents.length; i++) {
                latents[i] *= sigma;
            }

            UnetInputs inputs = UnetInputs.of(unet);
            float[] guidanceEmbedding = inputs.timestepCond == null ? null
                    : LcmScheduler.guidanceEmbedding(seeded.guidanceScale(), inputs.timestepCondDim);
            int[] timesteps = scheduler.timesteps();
            for (int step = 0; step < timesteps.length; step++) {
                progress.checkCancelled();
                progress.update("Step " + (step + 1) + " of " + timesteps.length,
                        0.12 + 0.78 * step / timesteps.length);
                float[] input = scheduler.scaleModelInput(latents, step);
                float[] noise = runUnet(env, inputs, input, latentShape, timesteps[step], condition, hiddenSize,
                        guidanceEmbedding);
                if (guidance) {
                    float[] uncond = runUnet(env, inputs, input, latentShape, timesteps[step], unconditional,
                            hiddenSize, guidanceEmbedding);
                    float scale = seeded.guidanceScale();
                    for (int i = 0; i < noise.length; i++) {
                        noise[i] = uncond[i] + scale * (noise[i] - uncond[i]);
                    }
                }
                latents = scheduler.step(noise, step, latents, random);
                if (preview != PreviewListener.NONE && step < timesteps.length - 1 && scheduler.denoised() != null) {
                    preview.preview(Images.previewSdLatents(scheduler.denoised(), latentWidth, latentHeight));
                }
            }
            progress.checkCancelled();
            progress.update("Decoding image", 0.92);
            var image = decode(env, latents, latentShape, seeded.width(), seeded.height());
            progress.update("Done", 1);
            return new GeneratedImage(image, id(), seeded, Duration.ofNanos(System.nanoTime() - started));
        } catch (OrtException | IOException e) {
            throw new ImageServiceException("Image generation failed: " + e.getMessage(), e);
        }
    }

    private float[] encode(OrtEnvironment env, String text) throws OrtException {
        int[] ids = tokenizer.encode(text);
        Map.Entry<String, NodeInfo> input = textEncoder.getInputInfo().entrySet().iterator().next();
        long[] shape = {1, ids.length};
        OnnxTensor tensor;
        if (((TensorInfo) input.getValue().getInfo()).type == OnnxJavaType.INT32) {
            tensor = OnnxTensor.createTensor(env, IntBuffer.wrap(ids), shape);
        } else {
            long[] longs = new long[ids.length];
            for (int i = 0; i < ids.length; i++) {
                longs[i] = ids[i];
            }
            tensor = OnnxTensor.createTensor(env, LongBuffer.wrap(longs), shape);
        }
        try (tensor; OrtSession.Result result = textEncoder.run(Map.of(input.getKey(), tensor))) {
            OnnxTensor hidden = (OnnxTensor) result.get("last_hidden_state")
                    .orElseGet(() -> result.get(0));
            return toArray(hidden.getFloatBuffer());
        }
    }

    private float[] runUnet(OrtEnvironment env, UnetInputs inputs, float[] sample, long[] latentShape, int timestep,
            float[] hidden, int hiddenSize, float[] guidanceEmbedding) throws OrtException {
        Map<String, OnnxTensor> feed = new HashMap<>();
        try {
            feed.put(inputs.sample, OnnxTensor.createTensor(env, FloatBuffer.wrap(sample), latentShape));
            feed.put(inputs.timestep, inputs.timestepTensor(env, timestep));
            feed.put(inputs.hidden, OnnxTensor.createTensor(env, FloatBuffer.wrap(hidden),
                    new long[] {1, hidden.length / hiddenSize, hiddenSize}));
            if (inputs.timestepCond != null) {
                feed.put(inputs.timestepCond, OnnxTensor.createTensor(env, FloatBuffer.wrap(guidanceEmbedding),
                        new long[] {1, guidanceEmbedding.length}));
            }
            try (OrtSession.Result result = unet.run(feed)) {
                return toArray(((OnnxTensor) result.get(0)).getFloatBuffer());
            }
        } finally {
            feed.values().forEach(OnnxTensor::close);
        }
    }

    private java.awt.image.BufferedImage decode(OrtEnvironment env, float[] latents, long[] latentShape, int width,
            int height) throws OrtException {
        float[] scaled = new float[latents.length];
        for (int i = 0; i < latents.length; i++) {
            scaled[i] = latents[i] / VAE_SCALE;
        }
        String name = vaeDecoder.getInputNames().iterator().next();
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(scaled), latentShape);
                OrtSession.Result result = vaeDecoder.run(Map.of(name, tensor))) {
            OnnxTensor output = (OnnxTensor) result.get(0);
            long[] shape = output.getInfo().getShape();
            return Images.fromPlanarSigned(toArray(output.getFloatBuffer()), (int) shape[3], (int) shape[2]);
        }
    }

    private static float[] toArray(FloatBuffer buffer) {
        float[] values = new float[buffer.remaining()];
        buffer.get(values);
        return values;
    }

    /** Names and types of the UNet inputs, which differ slightly between exporters. */
    record UnetInputs(String sample, String timestep, OnnxJavaType timestepType, boolean scalarTimestep,
            String hidden, String timestepCond, int timestepCondDim) {

        static UnetInputs of(OrtSession unet) throws OrtException {
            Map<String, NodeInfo> info = unet.getInputInfo();
            String sample = find(info, "sample");
            String timestep = find(info, "timestep");
            String hidden = find(info, "encoder_hidden_states");
            TensorInfo timestepInfo = (TensorInfo) info.get(timestep).getInfo();
            String cond = info.containsKey("timestep_cond") ? "timestep_cond" : null;
            int condDim = 256;
            if (cond != null) {
                long[] shape = ((TensorInfo) info.get(cond).getInfo()).getShape();
                if (shape.length == 2 && shape[1] > 0) {
                    condDim = (int) shape[1];
                }
            }
            OnnxJavaType type = timestepInfo.type;
            if (type == OnnxJavaType.FLOAT16 || ((TensorInfo) info.get(sample).getInfo()).type != OnnxJavaType.FLOAT) {
                throw new ImageServiceException("Only float32 ONNX exports are supported");
            }
            return new UnetInputs(sample, timestep, type, timestepInfo.getShape().length == 0, hidden, cond, condDim);
        }

        private static String find(Map<String, NodeInfo> info, String name) {
            if (info.containsKey(name)) {
                return name;
            }
            return info.keySet().stream().filter(k -> k.contains(name)).findFirst()
                    .orElseThrow(() -> new ImageServiceException("The UNet has no '" + name + "' input"));
        }

        OnnxTensor timestepTensor(OrtEnvironment env, int value) throws OrtException {
            long[] shape = scalarTimestep ? new long[0] : new long[] {1};
            return switch (timestepType) {
                case INT64 -> OnnxTensor.createTensor(env, LongBuffer.wrap(new long[] {value}), shape);
                case INT32 -> OnnxTensor.createTensor(env, IntBuffer.wrap(new int[] {value}), shape);
                default -> OnnxTensor.createTensor(env, FloatBuffer.wrap(new float[] {value}), shape);
            };
        }
    }

    @Override
    public synchronized void close() {
        Onnx.closeQuietly(textEncoder, unet, vaeDecoder);
        textEncoder = null;
        unet = null;
        vaeDecoder = null;
        if (tokenizer != null) {
            LOG.debug("Released {}", id());
        }
        tokenizer = null;
    }
}
