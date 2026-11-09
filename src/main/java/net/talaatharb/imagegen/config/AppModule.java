package net.talaatharb.imagegen.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;

import net.talaatharb.imagegen.generation.onnx.OnnxImageGenerationService;
import net.talaatharb.imagegen.generation.onnx.OnnxModels;
import net.talaatharb.imagegen.generation.sdcpp.SdCppEngine;
import net.talaatharb.imagegen.generation.sdcpp.SdCppImageGenerationService;
import net.talaatharb.imagegen.generation.sdcpp.SdCppModel;
import net.talaatharb.imagegen.generation.sdcpp.SdCppModels;
import net.talaatharb.imagegen.model.GpuRuntime;
import net.talaatharb.imagegen.model.HuggingFaceDownloader;
import net.talaatharb.imagegen.model.ModelStore;
import net.talaatharb.imagegen.service.ImageGenerationService;
import net.talaatharb.imagegen.service.ImageEditingService;
import net.talaatharb.imagegen.service.ImageUpscalingService;
import net.talaatharb.imagegen.generation.sdcpp.SdCppImageEditingService;
import net.talaatharb.imagegen.generation.sdcpp.SdCppUpscalingService;

/** Guice wiring for the application. */
public class AppModule extends AbstractModule {
    private final Path modelsRoot;
    private final AppSettings settings;

    public AppModule() {
        this(ModelStore.defaultRoot(), AppSettings.load());
    }

    public AppModule(Path modelsRoot, AppSettings settings) {
        this.modelsRoot = modelsRoot;
        this.settings = settings;
    }

    @Override
    protected void configure() {
        bind(ModelStore.class).toInstance(new ModelStore(modelsRoot));
        bind(AppSettings.class).toInstance(settings);
        bind(HuggingFaceDownloader.class).in(Singleton.class);
    }

    /** One stable-diffusion.cpp executable shared by every model; follows the GPU switch. */
    @Provides
    @Singleton
    SdCppEngine sdCppEngine(ModelStore store, HuggingFaceDownloader downloader) {
        return new SdCppEngine(store, downloader, GpuRuntime::isRequested);
    }

    /** Text-to-image models in the order they are offered in the UI (fastest first). */
    @Provides
    @Singleton
    List<ImageGenerationService> imageGenerationServices(SdCppEngine engine, ModelStore store,
            HuggingFaceDownloader downloader) {
        List<ImageGenerationService> services = new ArrayList<>();
        List<SdCppModel> models = SdCppModels.all();
        // SDXS and SD 1.5 + LCM first, then the in-process ONNX model, then the larger models.
        services.add(new SdCppImageGenerationService(models.get(0), engine, store, downloader));
        services.add(new SdCppImageGenerationService(models.get(1), engine, store, downloader));
        OnnxModels.all().forEach(m -> services.add(new OnnxImageGenerationService(m, store, downloader)));
        models.subList(2, models.size())
                .forEach(m -> services.add(new SdCppImageGenerationService(m, engine, store, downloader)));
        return List.copyOf(services);
    }

    @Provides @Singleton
    List<ImageEditingService> imageEditingServices(List<ImageGenerationService> generation) {
        return generation.stream().filter(SdCppImageGenerationService.class::isInstance)
                .map(SdCppImageGenerationService.class::cast)
                .filter(s -> List.of("sd15", "sd15-lcm", "flux2-klein-4b").contains(s.id()))
                .<ImageEditingService>map(SdCppImageEditingService::new).toList();
    }

    @Provides @Singleton
    List<ImageUpscalingService> imageUpscalingServices(SdCppEngine engine, ModelStore store,
            HuggingFaceDownloader downloader) {
        return List.of(
                new SdCppUpscalingService("realesrgan-x4plus", "Real-ESRGAN x4plus (general)",
                        "RealESRGAN_x4plus.pth", 67_040_989, engine, store, downloader),
                new SdCppUpscalingService("realesrgan-anime-6b", "Real-ESRGAN x4plus anime 6B (small)",
                        "RealESRGAN_x4plus_anime_6B.pth", 17_938_799, engine, store, downloader));
    }
}
