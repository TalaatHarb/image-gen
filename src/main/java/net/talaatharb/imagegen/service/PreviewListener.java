package net.talaatharb.imagegen.service;

import java.awt.image.BufferedImage;

/** Receives intermediate images while a model is sampling. */
@FunctionalInterface
public interface PreviewListener {
    PreviewListener NONE = image -> {
    };

    void preview(BufferedImage image);
}
