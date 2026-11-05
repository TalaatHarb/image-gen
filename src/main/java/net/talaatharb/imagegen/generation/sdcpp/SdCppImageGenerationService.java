package net.talaatharb.imagegen.generation.sdcpp;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

import javax.imageio.ImageIO;

import net.talaatharb.imagegen.model.HuggingFaceDownloader;
import net.talaatharb.imagegen.model.HuggingFaceDownloader.RemoteFile;
import net.talaatharb.imagegen.model.ModelStore;
import net.talaatharb.imagegen.model.ProgressListener;
import net.talaatharb.imagegen.service.GeneratedImage;
import net.talaatharb.imagegen.service.GenerationDefaults;
import net.talaatharb.imagegen.service.ImageGenerationRequest;
import net.talaatharb.imagegen.service.ImageGenerationService;
import net.talaatharb.imagegen.service.ImageServiceException;
import net.talaatharb.imagegen.service.PreviewListener;

/** Text-to-image through stable-diffusion.cpp for one {@link SdCppModel}. */
public class SdCppImageGenerationService implements ImageGenerationService {
    /** Shared Hugging Face file cache: {@code <models>/hf/<owner>--<repo>/<path>}. */
    public static final String CACHE_DIR = "hf";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SdCppModel model;
    private final SdCppEngine engine;
    private final ModelStore store;
    private final HuggingFaceDownloader downloader;

    public SdCppImageGenerationService(SdCppModel model, SdCppEngine engine, ModelStore store,
            HuggingFaceDownloader downloader) {
        this.model = model;
        this.engine = engine;
        this.store = store;
        this.downloader = downloader;
    }

    public SdCppModel model() {
        return model;
    }

    @Override
    public String id() {
        return model.id();
    }

    @Override
    public String displayName() {
        return model.displayName();
    }

    @Override
    public String description() {
        return model.description();
    }

    @Override
    public GenerationDefaults defaults() {
        return model.defaults();
    }

    /** Download size of the files that are not in the cache yet, including the engine (~20-30 MB). */
    @Override
    public long missingDownloadBytes() {
        long engineBytes = engine.isInstalled() ? 0 : 30L << 20;
        return engineBytes + model.components().stream().filter(c -> !Files.isRegularFile(path(store, c)))
                .mapToLong(SdCppModel.Component::sizeBytes).sum();
    }

    static Path path(ModelStore store, SdCppModel.Component component) {
        Path path = store.directory(CACHE_DIR).resolve(component.repo().replace("/", "--"));
        for (String part : component.path().split("/")) {
            path = path.resolve(part);
        }
        return path;
    }

    private List<RemoteFile> files() {
        // Shared files (e.g. the T5 encoder) may appear in several roles; download each once.
        Set<Path> seen = new LinkedHashSet<>();
        List<RemoteFile> files = new ArrayList<>();
        for (SdCppModel.Component c : model.components()) {
            Path target = path(store, c);
            if (seen.add(target)) {
                files.add(new RemoteFile(c.repo(), c.path(), target));
            }
        }
        return files;
    }

    @Override
    public boolean isInstalled() {
        return engine.isInstalled() && HuggingFaceDownloader.allPresent(files());
    }

    @Override
    public void install(ProgressListener progress) throws IOException {
        engine.install(progress.slice(0, 0.05));
        downloader.ensure(files(), progress.slice(0.05, 1));
    }

    @Override
    public GeneratedImage generate(ImageGenerationRequest request, ProgressListener progress,
            PreviewListener preview) {
        if (!isInstalled()) {
            throw new ImageServiceException(displayName() + " is not downloaded yet");
        }
        long started = System.nanoTime();
        ImageGenerationRequest seeded = request.randomSeed() ? request.withSeed(RANDOM.nextInt(Integer.MAX_VALUE))
                : request;
        Path work = null;
        try {
            work = Files.createTempDirectory("image-gen-");
            Path promptFile = work.resolve("prompt.txt");
            Path negativeFile = work.resolve("negative.txt");
            Path output = work.resolve("output.png");
            Path previewFile = work.resolve("preview.png");
            Files.writeString(promptFile, seeded.prompt().strip() + model.promptSuffix(), StandardCharsets.UTF_8);
            Files.writeString(negativeFile, seeded.negativePrompt().strip(), StandardCharsets.UTF_8);
            boolean previews = preview != PreviewListener.NONE && !"none".equals(model.preview());
            List<String> args = arguments(model, seeded, promptFile, negativeFile, output,
                    previews ? previewFile : null, c -> path(store, c));
            progress.update("Starting " + engine.describe(), 0);
            engine.run(args, output, previews ? previewFile : null, progress, preview);
            BufferedImage image = ImageIO.read(output.toFile());
            if (image == null) {
                throw new ImageServiceException("stable-diffusion.cpp wrote an unreadable image");
            }
            progress.update("Done", 1);
            return new GeneratedImage(image, id(), seeded, Duration.ofNanos(System.nanoTime() - started));
        } catch (IOException e) {
            throw new ImageServiceException("Image generation failed: " + e.getMessage(), e);
        } finally {
            if (work != null) {
                try {
                    SdCppEngine.deleteRecursively(work);
                } catch (IOException ignored) {
                    // Temporary files; the OS cleans them up eventually.
                }
            }
        }
    }

    /** Builds the {@code sd-cli} arguments (without the executable). */
    static List<String> arguments(SdCppModel model, ImageGenerationRequest request, Path promptFile,
            Path negativeFile, Path output, Path preview, Function<SdCppModel.Component, Path> resolver) {
        List<String> args = new ArrayList<>();
        for (SdCppModel.Component component : model.components()) {
            Path file = resolver.apply(component).toAbsolutePath();
            args.add(component.role().flag());
            args.add(component.role() == SdCppModel.Role.LORA ? file.getParent().toString() : file.toString());
        }
        args.addAll(model.extraArgs());
        args.add("--prompt-file");
        args.add(promptFile.toAbsolutePath().toString());
        if (model.defaults().supportsNegativePrompt() && !request.negativePrompt().isBlank()) {
            args.add("--negative-prompt-file");
            args.add(negativeFile.toAbsolutePath().toString());
        }
        args.addAll(List.of("-W", Integer.toString(request.width()), "-H", Integer.toString(request.height()),
                "--steps", Integer.toString(request.steps()),
                "--cfg-scale", String.format(Locale.ROOT, "%.2f", model.defaults().supportsGuidance()
                        ? request.guidanceScale() : 1f),
                "-s", Long.toString(request.seed())));
        if (model.sampler() != null) {
            args.add("--sampling-method");
            args.add(model.sampler());
        }
        if (preview != null) {
            args.addAll(List.of("--preview", model.preview(), "--preview-path", preview.toAbsolutePath().toString(),
                    "--preview-interval", "1"));
        }
        args.add("-o");
        args.add(output.toAbsolutePath().toString());
        return args;
    }
}
