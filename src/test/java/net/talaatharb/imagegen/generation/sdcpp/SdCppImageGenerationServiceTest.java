package net.talaatharb.imagegen.generation.sdcpp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.talaatharb.imagegen.model.ModelStore;
import net.talaatharb.imagegen.service.ImageGenerationRequest;

class SdCppImageGenerationServiceTest {
    private final ModelStore store = new ModelStore(Path.of("models"));
    private final Path prompt = Path.of("work", "prompt.txt");
    private final Path negative = Path.of("work", "negative.txt");
    private final Path output = Path.of("work", "output.png");

    private List<String> args(SdCppModel model, ImageGenerationRequest request, Path preview) {
        return SdCppImageGenerationService.arguments(model, request, prompt, negative, output, preview,
                c -> SdCppImageGenerationService.path(store, c));
    }

    private static String after(List<String> args, String flag) {
        int index = args.indexOf(flag);
        assertTrue(index >= 0, flag + " missing from " + args);
        return args.get(index + 1);
    }

    @Test
    void lcmLoraUsesTheLoraDirectoryAndSampler() {
        ImageGenerationRequest request = new ImageGenerationRequest("a cat", "blurry", 512, 384, 4, 1f, 7);
        List<String> args = args(SdCppModels.SD15_LCM, request, null);
        assertEquals(SdCppImageGenerationService.path(store, SdCppModels.SD15_LCM.components().get(1))
                .toAbsolutePath().getParent().toString(), after(args, "--lora-model-dir"));
        assertTrue(after(args, "-m").endsWith("stable-diffusion-v1-5-pruned-emaonly-Q8_0.gguf"));
        assertEquals("lcm", after(args, "--sampling-method"));
        assertEquals("512", after(args, "-W"));
        assertEquals("384", after(args, "-H"));
        assertEquals("4", after(args, "--steps"));
        assertEquals("1.00", after(args, "--cfg-scale"));
        assertEquals("7", after(args, "-s"));
        // Distilled models ignore negative prompts and previews are off without a preview file.
        assertFalse(args.contains("--negative-prompt-file"));
        assertFalse(args.contains("--preview"));
        assertEquals(output.toAbsolutePath().toString(), args.get(args.size() - 1));
    }

    @Test
    void guidedModelsPassGuidanceNegativePromptAndPreview() {
        ImageGenerationRequest request = new ImageGenerationRequest("a cat", "blurry", 512, 512, 20, 4.5f, 1);
        List<String> args = args(SdCppModels.PIXART_SIGMA, request, Path.of("work", "preview.png"));
        assertEquals("4.50", after(args, "--cfg-scale"));
        assertEquals(negative.toAbsolutePath().toString(), after(args, "--negative-prompt-file"));
        assertEquals("proj", after(args, "--preview"));
        assertTrue(after(args, "--diffusion-model").endsWith("diffusion_pytorch_model.safetensors"));
        assertTrue(after(args, "--t5xxl").endsWith("t5-v1_1-xxl-encoder-Q4_K_M.gguf"));
        assertEquals("pixart_pos_embed_base_size=32,pixart_interpolation_scale=1", after(args, "--model-args"));
    }

    @Test
    void sharedFilesLiveInOneCacheLocation() {
        Path pixartT5 = SdCppImageGenerationService.path(store, SdCppModels.PIXART_SIGMA.components().get(1));
        Path fluxT5 = SdCppImageGenerationService.path(store, SdCppModels.FLUX1_SCHNELL.components().get(2));
        assertEquals(pixartT5, fluxT5);
        assertEquals(store.directory("hf").resolve("city96--t5-v1_1-xxl-encoder-gguf")
                .resolve("t5-v1_1-xxl-encoder-Q4_K_M.gguf"), pixartT5);
    }

    @Test
    void catalogIdsAreUniqueAndDefaultsAreOffered() {
        Set<String> ids = new HashSet<>();
        for (SdCppModel model : SdCppModels.all()) {
            assertTrue(ids.add(model.id()), "duplicate id " + model.id());
            assertTrue(model.defaults().sizes().contains(model.defaults().width()), model.id());
            assertTrue(model.defaults().steps() <= model.defaults().maxSteps(), model.id());
            assertTrue(model.downloadSize() > 0, model.id());
        }
    }
}
