package net.talaatharb.imagegen.service;

import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.Objects;

/**
 * @param image   the generated picture
 * @param modelId {@link ImageModelService#id()} of the model that drew it
 * @param request the request with the seed that was actually used
 * @param elapsed wall-clock time including model loading
 */
public record GeneratedImage(BufferedImage image, String modelId, ImageGenerationRequest request, Duration elapsed) {
    public GeneratedImage {
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(request, "request");
    }
}
