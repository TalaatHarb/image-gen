package net.talaatharb.imagegen.generation.sdcpp;

import java.io.IOException;
import net.talaatharb.imagegen.model.ProgressListener;
import net.talaatharb.imagegen.service.GeneratedImage;
import net.talaatharb.imagegen.service.GenerationDefaults;
import net.talaatharb.imagegen.service.ImageEditingRequest;
import net.talaatharb.imagegen.service.ImageEditingService;
import net.talaatharb.imagegen.service.PreviewListener;

/** Shares generation checkpoints and their cache; only compatible models are offered in each mode. */
public final class SdCppImageEditingService implements ImageEditingService {
    private final SdCppImageGenerationService generation;
    public SdCppImageEditingService(SdCppImageGenerationService generation) {
        this.generation = generation;
    }
    @Override public String id() { return generation.id(); }
    @Override public String displayName() { return generation.displayName(); }
    @Override public String description() { return generation.description(); }
    @Override public boolean isInstalled() { return generation.isInstalled(); }
    @Override public long missingDownloadBytes() { return generation.missingDownloadBytes(); }
    @Override public void install(ProgressListener progress) throws IOException { generation.install(progress); }
    @Override public GenerationDefaults defaults() { return generation.defaults(); }
    @Override public boolean supports(ImageEditingRequest.Mode mode) {
        return mode == ImageEditingRequest.Mode.INSTRUCTION ? id().equals("flux2-klein-4b")
                : id().equals("sd15") || id().equals("sd15-lcm");
    }
    @Override public GeneratedImage edit(ImageEditingRequest request, ProgressListener progress, PreviewListener preview) {
        if (!supports(request.mode())) {
            throw new IllegalArgumentException(displayName() + " does not support " + request.mode());
        }
        return generation.process(request.generation(), request, progress, preview);
    }
}
