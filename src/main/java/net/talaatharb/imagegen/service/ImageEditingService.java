package net.talaatharb.imagegen.service;

public interface ImageEditingService extends ImageModelService {
    GenerationDefaults defaults();
    boolean supports(ImageEditingRequest.Mode mode);
    GeneratedImage edit(ImageEditingRequest request, net.talaatharb.imagegen.model.ProgressListener progress,
            PreviewListener preview);
}
