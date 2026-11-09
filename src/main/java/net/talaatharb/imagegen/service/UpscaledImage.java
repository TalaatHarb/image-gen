package net.talaatharb.imagegen.service;

import java.awt.image.BufferedImage;
import java.time.Duration;

public record UpscaledImage(BufferedImage image, String modelId, int scale, Duration elapsed) {
}
