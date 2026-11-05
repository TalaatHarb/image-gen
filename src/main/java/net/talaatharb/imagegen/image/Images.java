package net.talaatharb.imagegen.image;

import java.awt.image.BufferedImage;

/** Conversions between model tensors and images. */
public final class Images {
    /** Approximate linear map from Stable Diffusion 1.x/2.x latents to RGB, good enough for live previews. */
    private static final float[][] SD_LATENT_RGB = {
            {0.3512f, 0.2297f, 0.3227f},
            {0.3250f, 0.4974f, 0.2350f},
            {-0.2829f, 0.1762f, 0.2721f},
            {-0.2120f, -0.2616f, -0.7177f}};

    private Images() {
    }

    /** Converts a planar {@code [3, height, width]} tensor with values in [-1, 1] to an RGB image. */
    public static BufferedImage fromPlanarSigned(float[] data, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int plane = width * height;
        int[] pixels = new int[plane];
        for (int i = 0; i < plane; i++) {
            int r = toByte((data[i] + 1) / 2);
            int g = toByte((data[plane + i] + 1) / 2);
            int b = toByte((data[2 * plane + i] + 1) / 2);
            pixels[i] = (r << 16) | (g << 8) | b;
        }
        image.setRGB(0, 0, width, height, pixels, 0, width);
        return image;
    }

    /** A quick, low resolution preview of 4-channel Stable Diffusion latents ({@code [4, height, width]}). */
    public static BufferedImage previewSdLatents(float[] latents, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int plane = width * height;
        int[] pixels = new int[plane];
        for (int i = 0; i < plane; i++) {
            float r = 0;
            float g = 0;
            float b = 0;
            for (int c = 0; c < 4; c++) {
                float v = latents[c * plane + i];
                r += v * SD_LATENT_RGB[c][0];
                g += v * SD_LATENT_RGB[c][1];
                b += v * SD_LATENT_RGB[c][2];
            }
            pixels[i] = (toByte((r + 1) / 2) << 16) | (toByte((g + 1) / 2) << 8) | toByte((b + 1) / 2);
        }
        image.setRGB(0, 0, width, height, pixels, 0, width);
        return image;
    }

    private static int toByte(float value) {
        return Math.round(Math.max(0, Math.min(1, value)) * 255);
    }
}
