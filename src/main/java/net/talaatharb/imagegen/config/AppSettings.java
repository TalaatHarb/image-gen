package net.talaatharb.imagegen.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** User preferences persisted in {@code ~/.image-gen/settings.properties}. */
public class AppSettings {
    private static final Logger LOG = LoggerFactory.getLogger(AppSettings.class);
    private static final String GPU = "gpu.enabled";

    private final Path file;
    private final Properties properties = new Properties();

    public AppSettings(Path file) {
        this.file = file;
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                properties.load(in);
            } catch (IOException e) {
                LOG.warn("Could not read settings from {}", file, e);
            }
        }
    }

    public static AppSettings load() {
        return new AppSettings(Path.of(System.getProperty("user.home"), ".image-gen", "settings.properties"));
    }

    public synchronized boolean gpuEnabled() {
        return Boolean.parseBoolean(properties.getProperty(GPU, "false"));
    }

    public synchronized void setGpuEnabled(boolean enabled) {
        properties.setProperty(GPU, Boolean.toString(enabled));
        save();
    }

    public synchronized int mcpPort() {
        return Integer.parseInt(properties.getProperty("mcp.port", "8764"));
    }

    public synchronized String mcpModel() {
        return properties.getProperty("mcp.model", "sd15-lcm");
    }

    public synchronized void setMcpConfiguration(int port, String model) {
        if (port < 1 || port > 65535 || model == null || model.isBlank()) {
            throw new IllegalArgumentException("Choose a model and a port between 1 and 65535");
        }
        properties.setProperty("mcp.port", Integer.toString(port));
        properties.setProperty("mcp.model", model);
        save();
    }

    private void save() {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                properties.store(out, "Image Gen Studio settings");
            }
        } catch (IOException e) {
            LOG.warn("Could not save settings to {}", file, e);
        }
    }
}
