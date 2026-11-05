package net.talaatharb.imagegen.model;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Downloads model files from the Hugging Face Hub into the local cache (no Python or CLI needed). */
public class HuggingFaceDownloader {
    public static final String ENDPOINT_PROPERTY = "imagegen.hf.endpoint";
    private static final Logger LOG = LoggerFactory.getLogger(HuggingFaceDownloader.class);
    private static final int MAX_ATTEMPTS = 6;

    private final HttpClient client;
    private final String endpoint;

    public HuggingFaceDownloader() {
        this(System.getProperty(ENDPOINT_PROPERTY, "https://huggingface.co"));
    }

    public HuggingFaceDownloader(String endpoint) {
        this.endpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /** A file in a Hugging Face repository (or at a plain URL when {@code repo} is null) and its local location. */
    public record RemoteFile(String repo, String revision, String path, Path target) {
        public RemoteFile(String repo, String path, Path target) {
            this(repo, "main", path, target);
        }

        public static RemoteFile url(String url, Path target) {
            return new RemoteFile(null, null, url, target);
        }
    }

    public String url(String repo, String revision, String path) {
        StringBuilder encoded = new StringBuilder();
        for (String part : path.split("/")) {
            if (encoded.length() > 0) {
                encoded.append('/');
            }
            encoded.append(URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return endpoint + "/" + repo + "/resolve/" + revision + "/" + encoded;
    }

    /** Downloads every missing file, reporting combined progress. Existing files are kept. */
    public void ensure(List<RemoteFile> files, ProgressListener progress) throws IOException {
        int total = files.size();
        for (int i = 0; i < total; i++) {
            RemoteFile file = files.get(i);
            ProgressListener slice = progress.slice(i / (double) total, (i + 1) / (double) total);
            if (!Files.isRegularFile(file.target())) {
                download(file, slice);
            }
        }
        progress.update("All model files are available", 1);
    }

    public static boolean allPresent(List<RemoteFile> files) {
        return files.stream().allMatch(f -> Files.isRegularFile(f.target()));
    }

    /**
     * Downloads one file. Interrupted transfers are resumed from the partial {@code .part} file (HTTP range
     * requests), and dropped connections are retried a few times before giving up.
     */
    public void download(RemoteFile file, ProgressListener progress) throws IOException {
        Path target = file.target();
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path partial = target.resolveSibling(target.getFileName() + ".part");
        String url = file.repo() == null ? file.path() : url(file.repo(), file.revision(), file.path());
        String name = file.path().substring(file.path().lastIndexOf('/') + 1);
        LOG.info("Downloading {}", url);
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            progress.checkCancelled();
            try {
                if (transfer(url, name, partial, progress)) {
                    Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                    return;
                }
                last = new IOException("Download of " + name + " ended early");
            } catch (HttpStatusException e) {
                throw e;
            } catch (IOException e) {
                last = e;
            }
            LOG.warn("Download of {} interrupted (attempt {}/{}): {}", name, attempt, MAX_ATTEMPTS,
                    last.getMessage());
            sleep(Math.min(30_000L, 2_000L * attempt));
        }
        throw last;
    }

    /** Returns true when the partial file now holds the complete content. */
    private boolean transfer(String url, String name, Path partial, ProgressListener progress) throws IOException {
        long existing = Files.isRegularFile(partial) ? Files.size(partial) : 0;
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "image-gen-studio/1.0")
                .GET();
        if (existing > 0) {
            builder.header("Range", "bytes=" + existing + "-");
        }
        HttpResponse<InputStream> response;
        try {
            response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while downloading " + url, e);
        }
        int status = response.statusCode();
        if (status == 416 && existing > 0) {
            // The partial file already holds everything.
            response.body().close();
            return true;
        }
        if (status != 200 && status != 206) {
            response.body().close();
            throw new HttpStatusException("Download failed (HTTP " + status + "): " + url);
        }
        boolean append = status == 206;
        long done = append ? existing : 0;
        long remaining = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        long length = remaining < 0 ? -1 : done + remaining;
        OpenOption[] mode = append
                ? new OpenOption[] {StandardOpenOption.CREATE, StandardOpenOption.APPEND}
                : new OpenOption[] {StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE};
        try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(partial, mode)) {
            byte[] buffer = new byte[1 << 16];
            long lastReport = -1;
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (progress.isCancelled() || Thread.currentThread().isInterrupted()) {
                    throw new CancellationException("Cancelled");
                }
                out.write(buffer, 0, read);
                done += read;
                if (lastReport < 0 || done - lastReport > (1 << 20) || done == length) {
                    lastReport = done;
                    progress.update(String.format("Downloading %s (%s of %s)", name, size(done),
                            length > 0 ? size(length) : "?"), length > 0 ? done / (double) length : -1);
                }
            }
        }
        return length < 0 || done >= length;
    }

    private static void sleep(long millis) throws IOException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    /** A definitive HTTP error (missing file, gated repository, ...) that retrying won't fix. */
    static final class HttpStatusException extends IOException {
        HttpStatusException(String message) {
            super(message);
        }
    }
    static String size(long bytes) {
        if (bytes >= 1L << 30) {
            return String.format("%.2f GB", bytes / (double) (1L << 30));
        }
        if (bytes >= 1L << 20) {
            return String.format("%.1f MB", bytes / (double) (1L << 20));
        }
        return String.format("%.0f KB", bytes / 1024.0);
    }
}
