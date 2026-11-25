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

    public synchronized String sdCppDevice() {
        return properties.getProperty("gpu.sdcpp.device", "");
    }

    public synchronized String sdCppDeviceDescription() {
        return properties.getProperty("gpu.sdcpp.description", "");
    }

    public synchronized String directMlDevice() {
        return properties.getProperty("gpu.directml.device", "");
    }

    public synchronized void setGpuDevices(String device, String description, String directMl) {
        if (device == null || description == null || directMl == null
                || (!device.isEmpty() && (!device.matches("[A-Za-z0-9_]+") || description.isBlank()))
                || (device.isEmpty() && !description.isEmpty())
                || (!directMl.isEmpty() && !directMl.matches("[0-9]+"))) {
            throw new IllegalArgumentException("Invalid GPU device selection");
        }
        properties.setProperty("gpu.sdcpp.device", device);
        properties.setProperty("gpu.sdcpp.description", description);
        properties.setProperty("gpu.directml.device", directMl);
        save();
    }

    public synchronized int mcpPort() {
        return Integer.parseInt(properties.getProperty("mcp.port", "8764"));
    }

    public synchronized String mcpModel() {
        return properties.getProperty("mcp.model", "sd15-lcm");
    }

    public synchronized String mcpEditingModel() {
        return properties.getProperty("mcp.edit.model", "sd15");
    }

    public synchronized String mcpInstructionModel() {
        return properties.getProperty("mcp.instruction.model", "flux2-klein-4b");
    }

    public synchronized String mcpEditingMode() {
        return properties.getProperty("mcp.edit.mode", "img2img");
    }

    public synchronized float mcpEditingStrength() {
        return Float.parseFloat(properties.getProperty("mcp.edit.strength", "0.75"));
    }

    public synchronized String mcpUpscalingModel() {
        return properties.getProperty("mcp.upscale.model", "realesrgan-x4plus");
    }

    public synchronized int mcpUpscalingTileSize() {
        return Integer.parseInt(properties.getProperty("mcp.upscale.tile", "128"));
    }

    public synchronized void setMcpTaskDefaults(String editModel, String instructionModel, String editMode,
            float strength, String upscaleModel, int tile) {
        if (editModel == null || editModel.isBlank() || instructionModel == null || instructionModel.isBlank()
                || upscaleModel == null || upscaleModel.isBlank()
                || !java.util.List.of("img2img", "inpaint", "instruction").contains(editMode)
                || !Float.isFinite(strength) || strength <= 0 || strength > 1
                || tile < 32 || tile > 256 || tile % 32 != 0) {
            throw new IllegalArgumentException("Invalid editing/upscaling defaults");
        }
        properties.setProperty("mcp.edit.model", editModel);
        properties.setProperty("mcp.instruction.model", instructionModel);
        properties.setProperty("mcp.edit.mode", editMode);
        properties.setProperty("mcp.edit.strength", Float.toString(strength));
        properties.setProperty("mcp.upscale.model", upscaleModel);
        properties.setProperty("mcp.upscale.tile", Integer.toString(tile));
        save();
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
