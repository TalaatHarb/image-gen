package net.talaatharb.imagegen.service;

import java.awt.image.BufferedImage;
import net.talaatharb.imagegen.model.ProgressListener;

public interface ImageUpscalingService extends ImageModelService {
    int scale();
    UpscaledImage upscale(BufferedImage image, int tileSize, ProgressListener progress);
}
