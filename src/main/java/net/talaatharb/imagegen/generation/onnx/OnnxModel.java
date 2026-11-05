package net.talaatharb.imagegen.generation.onnx;

import java.util.List;

import net.talaatharb.imagegen.service.GenerationDefaults;

/**
 * A Stable Diffusion pipeline exported to ONNX in the diffusers / Optimum layout ({@code text_encoder},
 * {@code unet}, {@code vae_decoder}, {@code tokenizer} and {@code scheduler} folders) and run in-process by ONNX
 * Runtime, optionally on the GPU through DirectML.
 *
 * @param repo      Hugging Face repository
 * @param files     files of the repository that are needed (paths relative to the repository root)
 * @param sampler   sampler used for this model
 * @param sizeBytes approximate download size
 */
public record OnnxModel(String id, String displayName, String description, String repo, List<String> files,
        Sampler sampler, long sizeBytes, GenerationDefaults defaults) {

    public OnnxModel {
        files = List.copyOf(files);
    }

    public enum Sampler {
        /** Latent Consistency Model sampler; guidance is embedded in the UNet ({@code timestep_cond}). */
        LCM,
        /** Euler ancestral with classifier-free guidance. */
        EULER_ANCESTRAL
    }

    /** Files of a standard diffusers ONNX export; {@code externalUnetData} adds {@code unet/model.onnx_data}. */
    public static List<String> standardFiles(boolean externalUnetData, boolean specialTokens) {
        List<String> files = new java.util.ArrayList<>(List.of("tokenizer/vocab.json", "tokenizer/merges.txt",
                "scheduler/scheduler_config.json", "text_encoder/model.onnx", "vae_decoder/model.onnx",
                "unet/model.onnx"));
        if (externalUnetData) {
            files.add("unet/model.onnx_data");
        }
        if (specialTokens) {
            files.add("tokenizer/special_tokens_map.json");
        }
        return files;
    }
}
