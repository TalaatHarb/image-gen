package net.talaatharb.imagegen.service;

import java.util.List;

/**
 * Recommended generation settings of a model.
 *
 * @param width                  default width in pixels
 * @param height                 default height in pixels
 * @param steps                  default number of steps
 * @param maxSteps               upper bound offered in the UI
 * @param guidanceScale          default guidance scale (1 for distilled models)
 * @param supportsGuidance       false when the model ignores the guidance scale
 * @param supportsNegativePrompt false when the model ignores the negative prompt
 * @param sizes                  side lengths offered in the UI (the default size is always one of them)
 */
public record GenerationDefaults(int width, int height, int steps, int maxSteps, float guidanceScale,
        boolean supportsGuidance, boolean supportsNegativePrompt, List<Integer> sizes) {
    public GenerationDefaults {
        sizes = List.copyOf(sizes);
    }

    /** A distilled (few-step, guidance-free) model. */
    public static GenerationDefaults distilled(int size, int steps, int maxSteps, List<Integer> sizes) {
        return new GenerationDefaults(size, size, steps, maxSteps, 1f, false, false, sizes);
    }

    /** A model that uses classifier-free guidance and a negative prompt. */
    public static GenerationDefaults guided(int size, int steps, int maxSteps, float guidance, List<Integer> sizes) {
        return new GenerationDefaults(size, size, steps, maxSteps, guidance, true, true, sizes);
    }
}
