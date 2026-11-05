package net.talaatharb.imagegen.generation.sdcpp;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.talaatharb.imagegen.model.HuggingFaceDownloader;
import net.talaatharb.imagegen.model.ModelStore;
import net.talaatharb.imagegen.model.ProgressListener;
import net.talaatharb.imagegen.service.ImageServiceException;
import net.talaatharb.imagegen.service.PreviewListener;

/**
 * Runs the prebuilt stable-diffusion.cpp command line program ({@code sd-cli}). The release archive for the current
 * platform (about 17-30 MB) is downloaded once into the model cache, so nothing has to be installed; a custom build
 * can be used instead through {@value #PATH_PROPERTY}.
 */
public class SdCppEngine {
    /** Path of a custom {@code sd-cli} executable (e.g. a CUDA or ROCm build). */
    public static final String PATH_PROPERTY = "imagegen.sdcpp.path";
    private static final Logger LOG = LoggerFactory.getLogger(SdCppEngine.class);
    private static final long PREVIEW_POLL_MILLIS = 250;

    private final ModelStore store;
    private final HuggingFaceDownloader downloader;
    private final BooleanSupplier gpuRequested;
    private final Object runLock = new Object();

    /**
     * @param gpuRequested whether the GPU (Vulkan) build should be used; read on every run so the setting can change
     */
    public SdCppEngine(ModelStore store, HuggingFaceDownloader downloader, BooleanSupplier gpuRequested) {
        this.store = store;
        this.downloader = downloader;
        this.gpuRequested = gpuRequested;
    }

    /** The build that matches the current GPU preference, if one is published for this platform. */
    public SdCppPlatform platform() {
        return SdCppPlatform.current(gpuRequested.getAsBoolean()).orElseThrow(() -> new ImageServiceException(
                "No prebuilt stable-diffusion.cpp is published for " + System.getProperty("os.name") + " ("
                        + System.getProperty("os.arch") + "); build sd-cli yourself and set -D" + PATH_PROPERTY));
    }

    static Path customExecutable() {
        String custom = System.getProperty(PATH_PROPERTY);
        return custom == null || custom.isBlank() ? null : Paths.get(custom.trim());
    }

    public Path directory(SdCppPlatform platform) {
        return store.directory("sd-cpp-" + SdCppPlatform.RELEASE + "-" + platform.variant());
    }

    public Path executable() {
        Path custom = customExecutable();
        if (custom != null) {
            return custom;
        }
        SdCppPlatform platform = platform();
        return directory(platform).resolve(platform.executable());
    }

    /** Human readable name of the build in use, e.g. {@code stable-diffusion.cpp (vulkan)}. */
    public String describe() {
        if (customExecutable() != null) {
            return "stable-diffusion.cpp (custom build)";
        }
        return SdCppPlatform.current(gpuRequested.getAsBoolean())
                .map(p -> "stable-diffusion.cpp (" + p.variant() + ")")
                .orElse("stable-diffusion.cpp (unsupported platform)");
    }

    public boolean isInstalled() {
        Path custom = customExecutable();
        if (custom != null) {
            return Files.isRegularFile(custom);
        }
        return SdCppPlatform.current(gpuRequested.getAsBoolean())
                .map(p -> Files.isRegularFile(directory(p).resolve(p.executable())))
                .orElse(false);
    }

    /** Downloads and unpacks the release archive for this platform if it is missing. */
    public void install(ProgressListener progress) throws IOException {
        if (isInstalled()) {
            return;
        }
        Path custom = customExecutable();
        if (custom != null) {
            throw new IOException("The configured sd-cli does not exist: " + custom);
        }
        SdCppPlatform platform = platform();
        Path dir = directory(platform);
        Path archive = store.directory("downloads").resolve(platform.asset());
        downloader.download(HuggingFaceDownloader.RemoteFile.url(platform.url(), archive), progress.slice(0, 0.9));
        progress.update("Unpacking stable-diffusion.cpp", 0.95);
        extract(archive, dir);
        Files.deleteIfExists(archive);
        progress.update("stable-diffusion.cpp is available", 1);
    }

    static void extract(Path archive, Path dir) throws IOException {
        Path staging = dir.resolveSibling(dir.getFileName() + ".tmp");
        deleteRecursively(staging);
        Files.createDirectories(staging);
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path target = staging.resolve(entry.getName()).normalize();
                if (!target.startsWith(staging)) {
                    throw new IOException("Unsafe entry in archive: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                    makeExecutable(target);
                }
            }
        }
        deleteRecursively(dir);
        Files.move(staging, dir, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void makeExecutable(Path file) {
        try {
            if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
                var permissions = EnumSet.copyOf(Files.getPosixFilePermissions(file));
                permissions.addAll(EnumSet.of(PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_EXECUTE,
                        PosixFilePermission.OTHERS_EXECUTE));
                Files.setPosixFilePermissions(file, permissions);
            }
        } catch (IOException | UnsupportedOperationException e) {
            LOG.debug("Could not mark {} executable", file, e);
        }
    }

    static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            List<Path> paths = walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList();
            for (Path p : paths) {
                Files.deleteIfExists(p);
            }
        }
    }

    /**
     * Runs {@code sd-cli} with the given arguments and waits until it has written {@code output}. Only one run is
     * active at a time because a single run already uses every CPU core or most of the GPU memory.
     *
     * @param preview file that {@code sd-cli} rewrites after every step (null when previews are off)
     */
    public void run(List<String> arguments, Path output, Path preview, ProgressListener progress,
            PreviewListener previewListener) {
        synchronized (runLock) {
            runLocked(arguments, output, preview, progress, previewListener);
        }
    }

    private void runLocked(List<String> arguments, Path output, Path preview, ProgressListener progress,
            PreviewListener previewListener) {
        Path exe = executable();
        if (!Files.isRegularFile(exe)) {
            throw new ImageServiceException("stable-diffusion.cpp is not installed: " + exe);
        }
        List<String> command = new ArrayList<>();
        command.add(exe.toAbsolutePath().toString());
        command.addAll(arguments);
        LOG.info("Running {}", String.join(" ", command));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.directory(output.toAbsolutePath().getParent().toFile());
        addLibraryPath(builder.environment(), exe.toAbsolutePath().getParent());

        SdCppOutputParser parser = new SdCppOutputParser();
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new ImageServiceException("Could not start stable-diffusion.cpp: " + e.getMessage(), e);
        }
        Thread reader = new Thread(() -> pump(process.getInputStream(), parser, progress), "sd-cli-output");
        reader.setDaemon(true);
        reader.start();
        long lastPreview = 0;
        try {
            while (!process.waitFor(PREVIEW_POLL_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                if (progress.isCancelled() || Thread.currentThread().isInterrupted()) {
                    throw new CancellationException("Cancelled");
                }
                if (preview != null) {
                    lastPreview = publishPreview(preview, lastPreview, previewListener);
                }
            }
            reader.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            kill(process);
            throw new CancellationException("Cancelled");
        } catch (CancellationException e) {
            kill(process);
            throw e;
        }
        int exit = process.exitValue();
        if (exit != 0 || !Files.isRegularFile(output)) {
            // sd-cli sometimes exits with 0 although it could not create the image, so the output file decides.
            throw new ImageServiceException("stable-diffusion.cpp failed (exit code " + exit + "): "
                    + parser.failureSummary());
        }
    }

    private static void pump(InputStream stream, SdCppOutputParser parser, ProgressListener progress) {
        try (Reader in = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            StringBuilder line = new StringBuilder();
            int c;
            while ((c = in.read()) >= 0) {
                if (c == '\r' || c == '\n') {
                    if (line.length() > 0) {
                        handle(line.toString(), parser, progress);
                        line.setLength(0);
                    }
                } else {
                    line.append((char) c);
                }
            }
            if (line.length() > 0) {
                handle(line.toString(), parser, progress);
            }
        } catch (IOException e) {
            LOG.debug("sd-cli output closed", e);
        }
    }

    private static void handle(String line, SdCppOutputParser parser, ProgressListener progress) {
        LOG.debug("sd-cli: {}", line);
        SdCppOutputParser.Update update = parser.accept(line);
        if (update != null) {
            progress.update(update.message(), update.fraction());
        }
    }

    private static long publishPreview(Path preview, long lastModified, PreviewListener listener) {
        try {
            if (!Files.isRegularFile(preview)) {
                return lastModified;
            }
            long modified = Files.getLastModifiedTime(preview).toMillis();
            if (modified == lastModified) {
                return lastModified;
            }
            BufferedImage image = ImageIO.read(preview.toFile());
            if (image == null) {
                // Still being written; retry on the next poll.
                return lastModified;
            }
            listener.preview(image);
            return modified;
        } catch (IOException | RuntimeException e) {
            return lastModified;
        }
    }

    private static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The Linux and macOS builds ship their shared libraries next to the executable. */
    static void addLibraryPath(Map<String, String> environment, Path dir) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String variable;
        if (os.startsWith("windows")) {
            return;
        } else if (os.startsWith("mac") || os.startsWith("darwin")) {
            variable = "DYLD_LIBRARY_PATH";
        } else {
            variable = "LD_LIBRARY_PATH";
        }
        String existing = environment.get(variable);
        environment.put(variable, existing == null || existing.isBlank() ? dir.toString()
                : dir + java.io.File.pathSeparator + existing);
    }
}
