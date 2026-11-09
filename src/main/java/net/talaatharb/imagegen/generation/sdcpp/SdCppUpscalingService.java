package net.talaatharb.imagegen.generation.sdcpp;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import javax.imageio.ImageIO;
import org.slf4j.LoggerFactory;
import net.talaatharb.imagegen.model.HuggingFaceDownloader;
import net.talaatharb.imagegen.model.ModelStore;
import net.talaatharb.imagegen.model.ProgressListener;
import net.talaatharb.imagegen.service.ImageServiceException;
import net.talaatharb.imagegen.service.ImageUpscalingService;
import net.talaatharb.imagegen.service.PreviewListener;
import net.talaatharb.imagegen.service.UpscaledImage;

public final class SdCppUpscalingService implements ImageUpscalingService {
    private final String id;
    private final String name;
    private final String filename;
    private final long bytes;
    private final SdCppEngine engine;
    private final HuggingFaceDownloader downloader;
    private final Path model;

    public SdCppUpscalingService(String id, String name, String filename, long bytes,
            SdCppEngine engine, ModelStore store, HuggingFaceDownloader downloader) {
        this.id = id;
        this.name = name;
        this.filename = filename;
        this.bytes = bytes;
        this.engine = engine;
        this.downloader = downloader;
        model = store.directory("upscalers").resolve(filename);
    }
    @Override public String id() { return id; }
    @Override public String displayName() { return name; }
    @Override public String description() { return "Real-ESRGAN 4x neural upscaling; BSD-3-Clause. " + (bytes >> 20) + " MB."; }
    @Override public int scale() { return 4; }
    @Override public boolean isInstalled() { return engine.isInstalled() && Files.isRegularFile(model); }
    @Override public long missingDownloadBytes() {
        return (engine.isInstalled() ? 0 : 30L << 20) + (Files.isRegularFile(model) ? 0 : bytes);
    }
    @Override public void install(ProgressListener progress) throws IOException {
        engine.install(progress.slice(0, 0.1));
        downloader.download(HuggingFaceDownloader.RemoteFile.url(
                "https://github.com/xinntao/Real-ESRGAN/releases/download/"
                        + (id.equals("realesrgan-x4plus") ? "v0.1.0/" : "v0.2.2.4/") + filename,
                model), progress.slice(0.1, 1));
    }
    @Override public UpscaledImage upscale(BufferedImage input, int tileSize, ProgressListener progress) {
        if (input == null) { throw new IllegalArgumentException("Input image is required"); }
        if (tileSize < 32 || tileSize > 256 || tileSize % 32 != 0) {
            throw new IllegalArgumentException("Tile size must be a multiple of 32 between 32 and 256");
        }
        if ((long) input.getWidth() * input.getHeight() * 16 > 16_777_216) {
            throw new IllegalArgumentException("Upscaled output exceeds the 16 megapixel limit");
        }
        if (!isInstalled()) { throw new ImageServiceException(displayName() + " is not downloaded yet"); }
        Path work = null;
        long started = System.nanoTime();
        try {
            work = Files.createTempDirectory("image-upscale-");
            Path source = work.resolve("input.png");
            Path output = work.resolve("output.png");
            ImageIO.write(input, "png", source.toFile());
            engine.run(arguments(model, source, output, tileSize), output, null, progress, PreviewListener.NONE);
            BufferedImage image = ImageIO.read(output.toFile());
            if (image == null || image.getWidth() != input.getWidth() * scale()
                    || image.getHeight() != input.getHeight() * scale()) {
                throw new ImageServiceException("Upscaler did not produce the expected 4x image");
            }
            return new UpscaledImage(image, id, scale(), Duration.ofNanos(System.nanoTime() - started));
        } catch (IOException e) {
            throw new ImageServiceException("Upscaling failed: " + e.getMessage(), e);
        } finally {
            if (work != null) {
                try { SdCppEngine.deleteRecursively(work); }
                catch (IOException e) { LoggerFactory.getLogger(getClass()).warn("Could not remove {}", work, e); }
            }
        }
    }
    static List<String> arguments(Path model, Path source, Path output, int tile) {
        return List.of("--mode", "upscale", "--upscale-model", model.toAbsolutePath().toString(),
                "--init-img", source.toAbsolutePath().toString(), "--upscale-repeats", "1",
                "--upscale-tile-size", Integer.toString(tile), "-o", output.toAbsolutePath().toString());
    }
}
