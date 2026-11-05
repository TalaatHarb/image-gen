package net.talaatharb.imagegen.generation.sdcpp;

import static net.talaatharb.imagegen.generation.sdcpp.SdCppModel.Role.CHECKPOINT;
import static net.talaatharb.imagegen.generation.sdcpp.SdCppModel.Role.CLIP_L;
import static net.talaatharb.imagegen.generation.sdcpp.SdCppModel.Role.DIFFUSION;
import static net.talaatharb.imagegen.generation.sdcpp.SdCppModel.Role.LLM;
import static net.talaatharb.imagegen.generation.sdcpp.SdCppModel.Role.LORA;
import static net.talaatharb.imagegen.generation.sdcpp.SdCppModel.Role.T5XXL;
import static net.talaatharb.imagegen.generation.sdcpp.SdCppModel.Role.VAE;

import java.util.List;

import net.talaatharb.imagegen.generation.sdcpp.SdCppModel.Component;
import net.talaatharb.imagegen.service.GenerationDefaults;

/**
 * Models that run through stable-diffusion.cpp, smallest first. Every file comes from an ungated Hugging Face
 * repository; quantized (GGUF) builds keep the big transformer models within reach of CPUs and 4-8 GB GPUs.
 */
public final class SdCppModels {
    private static final long MB = 1L << 20;

    private static final List<Integer> SD_SIZES = List.of(256, 320, 384, 448, 512, 576, 640, 704, 768);
    private static final List<Integer> LARGE_SIZES = List.of(256, 384, 512, 640, 768, 896, 1024);

    // Files shared by several models are downloaded once.
    private static final Component SD15 = new Component(CHECKPOINT, "second-state/stable-diffusion-v1-5-GGUF",
            "stable-diffusion-v1-5-pruned-emaonly-Q8_0.gguf", 1_682 * MB);
    private static final Component T5_XXL = new Component(T5XXL, "city96/t5-v1_1-xxl-encoder-gguf",
            "t5-v1_1-xxl-encoder-Q4_K_M.gguf", 2_762 * MB);
    private static final Component FLUX_AE = new Component(VAE, "second-state/FLUX.1-schnell-GGUF",
            "ae.safetensors", 320 * MB);

    public static final SdCppModel SDXS = new SdCppModel("sdxs-512", "SDXS-512 DreamShaper (1 step)",
            "Stable Diffusion distilled to a single step with a tiny decoder; a few seconds per image on a CPU. "
                    + "0.9 GB, OpenRAIL++.",
            List.of(new Component(CHECKPOINT, "akleine/sdxs-512", "sdxs.safetensors", 842 * MB)),
            List.of(), null, "", "proj", GenerationDefaults.distilled(512, 1, 4, SD_SIZES));

    public static final SdCppModel SD15_LCM = new SdCppModel("sd15-lcm", "Stable Diffusion 1.5 + LCM-LoRA (4 steps)",
            "Stable Diffusion 1.5 (Q8_0) with the Latent Consistency LoRA: good quality in 4 steps. "
                    + "1.8 GB, CreativeML OpenRAIL-M.",
            List.of(SD15, new Component(LORA, "latent-consistency/lcm-lora-sdv1-5", "pytorch_lora_weights.safetensors",
                    128 * MB)),
            List.of(), "lcm", " <lora:pytorch_lora_weights:1>", "proj",
            GenerationDefaults.distilled(512, 4, 12, SD_SIZES));

    public static final SdCppModel SD15_MODEL = new SdCppModel("sd15", "Stable Diffusion 1.5",
            "The classic 512 px model (Q8_0) with negative prompts and guidance; 20+ steps. "
                    + "1.7 GB, CreativeML OpenRAIL-M.",
            List.of(SD15), List.of(), "euler_a", "", "proj",
            GenerationDefaults.guided(512, 20, 50, 7f, SD_SIZES));

    public static final SdCppModel SD_TURBO = new SdCppModel("sd-turbo", "SD-Turbo (1-4 steps)",
            "Adversarially distilled Stable Diffusion 2.1 for 1-4 step images at 512 px. "
                    + "1.9 GB, Stability AI Community License (non-commercial above revenue limits).",
            List.of(new Component(CHECKPOINT, "Green-Sky/SD-Turbo-GGUF", "sd_turbo-f16-q8_0.gguf", 1_930 * MB)),
            List.of(), "euler_a", "", "proj", GenerationDefaults.distilled(512, 2, 8, SD_SIZES));

    public static final SdCppModel PIXART_SIGMA = new SdCppModel("pixart-sigma-512", "PixArt-Σ 512",
            "Diffusion transformer with a T5 text encoder (Q4_K_M) that follows long prompts well; 20 steps. "
                    + "5.3 GB, OpenRAIL++.",
            List.of(new Component(DIFFUSION, "PixArt-alpha/PixArt-Sigma-XL-2-512-MS",
                    "transformer/diffusion_pytorch_model.safetensors", 2_330 * MB), T5_XXL,
                    new Component(VAE, "PixArt-alpha/PixArt-Sigma-XL-2-1024-MS",
                            "vae/diffusion_pytorch_model.safetensors", 319 * MB)),
            List.of("--model-args", "pixart_pos_embed_base_size=32,pixart_interpolation_scale=1"), null, "",
            "proj", GenerationDefaults.guided(512, 20, 50, 4.5f, SD_SIZES));

    public static final SdCppModel PIXART_ALPHA = new SdCppModel("pixart-alpha-512", "PixArt-α 512",
            "The original PixArt diffusion transformer at 512 px with a T5 text encoder (Q4_K_M); 20 steps. "
                    + "5.1 GB, OpenRAIL++.",
            List.of(new Component(DIFFUSION, "PixArt-alpha/PixArt-XL-2-512x512",
                    "transformer/diffusion_pytorch_model.safetensors", 2_332 * MB), T5_XXL,
                    new Component(VAE, "PixArt-alpha/PixArt-XL-2-512x512", "vae/diffusion_pytorch_model.fp16.safetensors",
                            160 * MB)),
            List.of("--model-args",
                    "pixart_pos_embed_base_size=32,pixart_interpolation_scale=1,pixart_vae_scale_factor=0.18215"),
            null, "", "proj", GenerationDefaults.guided(512, 20, 50, 4.5f, SD_SIZES));

    public static final SdCppModel FLUX2_KLEIN = new SdCppModel("flux2-klein-4b", "FLUX.2 [klein] 4B (Q4_0)",
            "Black Forest Labs' compact FLUX.2 model, distilled to 4 steps, with a Qwen3-4B text encoder. "
                    + "4.9 GB, Apache 2.0.",
            List.of(new Component(DIFFUSION, "leejet/FLUX.2-klein-4B-GGUF", "flux-2-klein-4b-Q4_0.gguf", 2_346 * MB),
                    new Component(LLM, "unsloth/Qwen3-4B-GGUF", "Qwen3-4B-Q4_K_M.gguf", 2_382 * MB),
                    new Component(VAE, "Comfy-Org/Flux-2-Klein-4B", "split_files/vae/flux2-vae.safetensors",
                            321 * MB)),
            List.of(), null, "", "proj", GenerationDefaults.distilled(512, 4, 12, LARGE_SIZES));

    public static final SdCppModel FLUX1_SCHNELL = new SdCppModel("flux1-schnell", "FLUX.1 [schnell] (Q3_K)",
            "12B rectified-flow transformer distilled to 4 steps; best prompt following here but the slowest on a "
                    + "CPU. 8.1 GB, Apache 2.0.",
            List.of(new Component(DIFFUSION, "leejet/FLUX.1-schnell-gguf", "flux1-schnell-q3_k.gguf", 5_067 * MB),
                    new Component(CLIP_L, "second-state/FLUX.1-schnell-GGUF", "clip_l-Q8_0.gguf", 125 * MB), T5_XXL,
                    FLUX_AE),
            List.of(), null, "", "proj", GenerationDefaults.distilled(512, 4, 12, LARGE_SIZES));

    public static final SdCppModel Z_IMAGE_TURBO = new SdCppModel("z-image-turbo", "Z-Image-Turbo (Q3_K)",
            "Alibaba's 6B single-stream transformer distilled to 8 steps, with a Qwen3-4B text encoder; strong "
                    + "photorealism and text rendering. 5.6 GB, Apache 2.0.",
            List.of(new Component(DIFFUSION, "leejet/Z-Image-Turbo-GGUF", "z_image_turbo-Q3_K.gguf", 2_998 * MB),
                    new Component(LLM, "unsloth/Qwen3-4B-Instruct-2507-GGUF", "Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
                            2_382 * MB),
                    FLUX_AE),
            List.of(), null, "", "proj", GenerationDefaults.distilled(512, 8, 16, LARGE_SIZES));

    private SdCppModels() {
    }

    public static List<SdCppModel> all() {
        return List.of(SDXS, SD15_LCM, SD15_MODEL, SD_TURBO, PIXART_SIGMA, PIXART_ALPHA, FLUX2_KLEIN, FLUX1_SCHNELL,
                Z_IMAGE_TURBO);
    }
}
