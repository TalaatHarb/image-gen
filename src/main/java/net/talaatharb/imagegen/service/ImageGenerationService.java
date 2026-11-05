package net.talaatharb.imagegen.service;

import net.talaatharb.imagegen.model.ProgressListener;

/** Creates an image from a text prompt. */
public interface ImageGenerationService extends ImageModelService {
    /** Recommended settings for this model; the UI starts from these when the model is selected. */
    GenerationDefaults defaults();

    /**
     * @param preview receives intermediate images while sampling (may be called from any thread); use
     *                {@link PreviewListener#NONE} to ignore them
     */
    GeneratedImage generate(ImageGenerationRequest request, ProgressListener progress, PreviewListener preview);
}
