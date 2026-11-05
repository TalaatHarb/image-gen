package net.talaatharb.imagegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import net.talaatharb.imagegen.generation.onnx.OnnxImageGenerationService;
import net.talaatharb.imagegen.generation.onnx.OnnxModel;
import net.talaatharb.imagegen.generation.onnx.OnnxModels;
import net.talaatharb.imagegen.generation.sdcpp.SdCppEngine;
import net.talaatharb.imagegen.generation.sdcpp.SdCppImageGenerationService;
import net.talaatharb.imagegen.generation.sdcpp.SdCppModel;
import net.talaatharb.imagegen.generation.sdcpp.SdCppModels;
import net.talaatharb.imagegen.model.HuggingFaceDownloader;
import net.talaatharb.imagegen.model.ModelStore;
import net.talaatharb.imagegen.model.ProgressListener;
import net.talaatharb.imagegen.service.GeneratedImage;
import net.talaatharb.imagegen.service.GenerationDefaults;
import net.talaatharb.imagegen.service.ImageGenerationRequest;
import net.talaatharb.imagegen.service.ImageGenerationService;

/**
 * Downloads and runs real models; enabled with {@code -Dimagegen.integration=true} (and optionally
 * {@code -Dimagegen.models=<dir>} for the cache). Results are written to {@code target/integration-images}.
 */
@EnabledIfSystemProperty(named = "imagegen.integration", matches = "true")
class ModelsIntegrationTest {
    private static final String PROMPT = "a photo of a red fox sitting in a green meadow, sharp focus";

    private final ModelStore store = new ModelStore(ModelStore.defaultRoot());
    private final HuggingFaceDownloader downloader = new HuggingFaceDownloader();
    private final SdCppEngine engine = new SdCppEngine(store, downloader, () -> false);

    private static final ProgressListener LOG = (message, fraction) -> System.out.printf("  %5.1f%% %s%n",
            fraction < 0 ? 0 : fraction * 100, message);

    private GeneratedImage generate(ImageGenerationService service, ImageGenerationRequest request)
            throws IOException {
        service.install(LOG);
        List<BufferedImage> previews = new ArrayList<>();
        try {
            GeneratedImage image = service.generate(request, LOG, previews::add);
            Path dir = Path.of("target", "integration-images");
            Files.createDirectories(dir);
            ImageIO.write(image.image(), "png", dir.resolve(service.id() + ".png").toFile());
            System.out.printf("%s: %s in %d ms, %d previews%n", service.id(), request, image.elapsed().toMillis(),
                    previews.size());
            return image;
        } finally {
            service.close();
        }
    }

    private void runSdCpp(SdCppModel model) throws IOException {
        GenerationDefaults d = model.defaults();
        GeneratedImage image = generate(new SdCppImageGenerationService(model, engine, store, downloader),
                new ImageGenerationRequest(PROMPT, "blurry", d.width(), d.height(), d.steps(), d.guidanceScale(), 7));
        assertEquals(d.width(), image.image().getWidth());
        assertEquals(d.height(), image.image().getHeight());
        assertEquals(7, image.request().seed());
        assertTrue(contrast(image.image()) > 20, "image looks blank");
    }

    @Test
    void sdxsThroughStableDiffusionCpp() throws IOException {
        runSdCpp(SdCppModels.SDXS);
    }

    @Test
    void sd15WithLcmLoraThroughStableDiffusionCpp() throws IOException {
        runSdCpp(SdCppModels.SD15_LCM);
    }

    @Test
    void tinyOnnxPipelineWiresAllTensors() throws IOException {
        // Random weights: only checks that tokenizer, text encoder, UNet, sampler and VAE fit together.
        OnnxModel tiny = new OnnxModel("tiny-sd-onnx", "Tiny SD (test)", "test",
                "optimum-internal-testing/tiny-stable-diffusion-onnx", OnnxModel.standardFiles(false, false),
                OnnxModel.Sampler.EULER_ANCESTRAL, 8L << 20,
                GenerationDefaults.guided(128, 2, 4, 7f, List.of(128)));
        GeneratedImage image = generate(new OnnxImageGenerationService(tiny, store, downloader),
                new ImageGenerationRequest(PROMPT, "blurry", 128, 128, 2, 7f, -1));
        // The tiny VAE only upsamples 2x (real SD VAEs upsample 8x).
        assertEquals(32, image.image().getWidth());
        assertTrue(image.request().seed() >= 0);
    }

    @Test
    void lcmDreamshaperOnOnnxRuntime() throws IOException {
        GenerationDefaults d = OnnxModels.LCM_DREAMSHAPER.defaults();
        GeneratedImage image = generate(new OnnxImageGenerationService(OnnxModels.LCM_DREAMSHAPER, store,
                downloader), new ImageGenerationRequest(PROMPT, "", 512, 512, d.steps(), d.guidanceScale(), 7));
        assertEquals(512, image.image().getWidth());
        assertTrue(contrast(image.image()) > 20, "image looks blank");
    }

    /** Standard deviation of the luminance; near 0 for blank or uniform images. */
    static double contrast(BufferedImage image) {
        double sum = 0;
        double squares = 0;
        int count = 0;
        for (int y = 0; y < image.getHeight(); y += 4) {
            for (int x = 0; x < image.getWidth(); x += 4) {
                int rgb = image.getRGB(x, y);
                double l = 0.299 * ((rgb >> 16) & 0xff) + 0.587 * ((rgb >> 8) & 0xff) + 0.114 * (rgb & 0xff);
                sum += l;
                squares += l * l;
                count++;
            }
        }
        double mean = sum / count;
        return Math.sqrt(Math.max(0, squares / count - mean * mean));
    }
}
