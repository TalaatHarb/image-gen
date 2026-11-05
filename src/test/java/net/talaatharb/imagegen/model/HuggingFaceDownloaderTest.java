package net.talaatharb.imagegen.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

class HuggingFaceDownloaderTest {
    private final byte[] content = new byte[300_000];
    private final List<String> ranges = new ArrayList<>();
    private HttpServer server;
    private String endpoint;

    @TempDir
    Path dir;

    @BeforeEach
    void start() throws IOException {
        new Random(3).nextBytes(content);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/owner/repo/resolve/main/model.bin", exchange -> {
            String range = exchange.getRequestHeaders().getFirst("Range");
            ranges.add(String.valueOf(range));
            int from = range == null ? 0 : Integer.parseInt(range.replaceAll("\\D", ""));
            exchange.sendResponseHeaders(range == null ? 200 : 206, content.length - from);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(content, from, content.length - from);
            }
        });
        server.createContext("/owner/repo/resolve/main/missing.bin", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void downloadsAFile() throws IOException {
        Path target = dir.resolve("a").resolve("model.bin");
        new HuggingFaceDownloader(endpoint).download(
                new HuggingFaceDownloader.RemoteFile("owner/repo", "model.bin", target), ProgressListener.NONE);
        assertArrayEquals(content, Files.readAllBytes(target));
        assertFalse(Files.exists(target.resolveSibling("model.bin.part")));
    }

    @Test
    void resumesAPartialDownload() throws IOException {
        Path target = dir.resolve("model.bin");
        Files.write(target.resolveSibling("model.bin.part"), java.util.Arrays.copyOf(content, 1000));
        new HuggingFaceDownloader(endpoint).download(
                new HuggingFaceDownloader.RemoteFile("owner/repo", "model.bin", target), ProgressListener.NONE);
        assertEquals(List.of("bytes=1000-"), ranges);
        assertArrayEquals(content, Files.readAllBytes(target));
    }

    @Test
    void doesNotRetryMissingFiles() {
        Path target = dir.resolve("missing.bin");
        IOException error = assertThrows(IOException.class, () -> new HuggingFaceDownloader(endpoint).download(
                new HuggingFaceDownloader.RemoteFile("owner/repo", "missing.bin", target), ProgressListener.NONE));
        assertEquals(true, error.getMessage().contains("HTTP 404"));
    }
}
