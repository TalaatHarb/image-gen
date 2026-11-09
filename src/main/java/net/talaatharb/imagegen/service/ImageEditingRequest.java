package net.talaatharb.imagegen.service;

import java.awt.image.BufferedImage;
import java.util.Objects;

public record ImageEditingRequest(BufferedImage image, BufferedImage mask, Mode mode,
        ImageGenerationRequest generation, float strength) {
    public enum Mode {
        IMG2IMG("img2img"), INPAINT("inpaint"), INSTRUCTION("instruction");
        private final String id;
        Mode(String id) { this.id = id; }
        public String id() { return id; }
        @Override public String toString() { return id; }
    }

    public ImageEditingRequest {
        Objects.requireNonNull(image, "Input image is required");
        Objects.requireNonNull(mode, "Editing mode is required");
        Objects.requireNonNull(generation, "Editing prompt is required");
        if ((long) image.getWidth() * image.getHeight() > net.talaatharb.imagegen.image.ImageInputs.MAX_PIXELS) {
            throw new IllegalArgumentException("Input image exceeds the 16 megapixel limit");
        }
        if (!Float.isFinite(strength) || strength <= 0 || strength > 1) {
            throw new IllegalArgumentException("Strength must be greater than 0 and at most 1");
        }
        if (mode == Mode.INPAINT && (mask == null || mask.getWidth() != image.getWidth()
                || mask.getHeight() != image.getHeight())) {
            throw new IllegalArgumentException("Inpainting needs a mask with the same dimensions as the input image");
        }
    }
}
