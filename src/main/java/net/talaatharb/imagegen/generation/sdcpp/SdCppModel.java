package net.talaatharb.imagegen.generation.sdcpp;

import java.util.List;
import java.util.Objects;

import net.talaatharb.imagegen.service.GenerationDefaults;

/**
 * Describes how stable-diffusion.cpp runs one model: which files it needs, how they are passed on the command line
 * and the recommended settings.
 *
 * @param id           stable identifier
 * @param displayName  name shown in the UI
 * @param description  one-line summary (size, speed, license)
 * @param components   model files, downloaded from Hugging Face
 * @param extraArgs    additional fixed command line arguments
 * @param sampler      {@code --sampling-method}, or null for stable-diffusion.cpp's per-model default
 * @param promptSuffix appended to the prompt (e.g. {@code <lora:name:1>} to apply a LoRA)
 * @param preview      {@code --preview} method used for live previews ({@code none} disables them)
 * @param defaults     recommended generation settings
 */
public record SdCppModel(String id, String displayName, String description, List<Component> components,
        List<String> extraArgs, String sampler, String promptSuffix, String preview, GenerationDefaults defaults) {

    public SdCppModel {
        Objects.requireNonNull(id, "id");
        components = List.copyOf(components);
        extraArgs = List.copyOf(extraArgs);
        promptSuffix = promptSuffix == null ? "" : promptSuffix;
        preview = preview == null ? "none" : preview;
    }

    /** How a model file is handed to {@code sd-cli}. */
    public enum Role {
        /** A complete checkpoint ({@code -m}). */
        CHECKPOINT("-m"),
        /** A standalone diffusion model / transformer ({@code --diffusion-model}). */
        DIFFUSION("--diffusion-model"),
        VAE("--vae"),
        CLIP_L("--clip_l"),
        T5XXL("--t5xxl"),
        /** A language model used as the text encoder (FLUX.2, Z-Image). */
        LLM("--llm"),
        /** A LoRA; its directory is passed with {@code --lora-model-dir} and it is applied by the prompt suffix. */
        LORA("--lora-model-dir");

        private final String flag;

        Role(String flag) {
            this.flag = flag;
        }

        public String flag() {
            return flag;
        }
    }

    /**
     * A file in a Hugging Face repository.
     *
     * @param sizeBytes approximate download size, used for the download confirmation
     */
    public record Component(Role role, String repo, String path, long sizeBytes) {
    }

    public long downloadSize() {
        return components.stream().mapToLong(Component::sizeBytes).sum();
    }
}
