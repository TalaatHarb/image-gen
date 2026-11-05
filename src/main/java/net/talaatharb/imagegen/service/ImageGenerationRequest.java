package net.talaatharb.imagegen.service;

import java.util.Objects;

/**
 * @param prompt         what to draw
 * @param negativePrompt what to avoid; ignored by models without classifier-free guidance
 * @param width          output width in pixels (a multiple of 64)
 * @param height         output height in pixels (a multiple of 64)
 * @param steps          number of denoising steps
 * @param guidanceScale  classifier-free guidance scale; 1 disables guidance (distilled models)
 * @param seed           random seed; a negative value picks a random one
 */
public record ImageGenerationRequest(String prompt, String negativePrompt, int width, int height, int steps,
        float guidanceScale, long seed) {
    public static final int SIZE_MULTIPLE = 64;

    public ImageGenerationRequest {
        Objects.requireNonNull(prompt, "prompt");
        if (prompt.isBlank()) {
            throw new IllegalArgumentException("Enter a prompt describing the image");
        }
        negativePrompt = negativePrompt == null ? "" : negativePrompt;
        if (width < SIZE_MULTIPLE || height < SIZE_MULTIPLE || width % SIZE_MULTIPLE != 0
                || height % SIZE_MULTIPLE != 0) {
            throw new IllegalArgumentException("Width and height must be positive multiples of " + SIZE_MULTIPLE);
        }
        if (steps < 1) {
            throw new IllegalArgumentException("At least one step is needed");
        }
        if (guidanceScale < 0) {
            throw new IllegalArgumentException("Guidance scale can't be negative");
        }
    }

    public boolean randomSeed() {
        return seed < 0;
    }

    /** The same request with a concrete seed. */
    public ImageGenerationRequest withSeed(long value) {
        return new ImageGenerationRequest(prompt, negativePrompt, width, height, steps, guidanceScale, value);
    }
}
