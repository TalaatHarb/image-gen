package net.talaatharb.imagegen.generation.sdcpp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Recovers missing Vulkan registration using manifests for an active adapter's registered OpenGL DLL. */
final class WindowsVulkanDrivers {
    private static final Logger LOG = LoggerFactory.getLogger(WindowsVulkanDrivers.class);
    private static final String DISCOVER = """
            $ErrorActionPreference = 'Stop'
            $ProgressPreference = 'SilentlyContinue'
            [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
            $class = 'HKLM:\\SYSTEM\\CurrentControlSet\\Control\\Class\\'
            Get-CimInstance Win32_VideoController | ForEach-Object {
                $device = Get-ItemProperty -LiteralPath ('HKLM:\\SYSTEM\\CurrentControlSet\\Enum\\' + $_.PNPDeviceID)
                if ($device.Driver) {
                    $driver = Get-ItemProperty -LiteralPath ($class + $device.Driver)
                    if (-not $driver.VulkanDriverName) {
                        $driver.OpenGLDriverName | ForEach-Object { if ($_){ [string]$_ } }
                    }
                }
            }
            """;
    private static List<Path> cached;

    private WindowsVulkanDrivers() {
    }

    static void configure(Map<String, String> environment) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (!os.startsWith("windows") || !(arch.equals("amd64") || arch.equals("x86_64"))
                || hasOverride(environment)) {
            return;
        }
        addManifests(environment, discover());
    }

    private static boolean hasOverride(Map<String, String> environment) {
        return environment.containsKey("VK_DRIVER_FILES") || environment.containsKey("VK_ICD_FILENAMES")
                || environment.containsKey("VK_ADD_DRIVER_FILES");
    }

    static void addManifests(Map<String, String> environment, List<Path> manifests) {
        if (!hasOverride(environment) && !manifests.isEmpty()) {
            environment.put("VK_ADD_DRIVER_FILES", String.join(";", manifests.stream().map(Path::toString).toList()));
        }
    }

    private static synchronized List<Path> discover() {
        if (cached != null) {
            return cached;
        }
        List<Path> manifests = new ArrayList<>();
        Path output = null;
        Process process = null;
        try {
            output = Files.createTempFile("image-gen-vulkan-", ".txt");
            String encoded = Base64.getEncoder().encodeToString(DISCOVER.getBytes(StandardCharsets.UTF_16LE));
            Path powershell = Path.of(System.getenv("SystemRoot"), "System32", "WindowsPowerShell", "v1.0",
                    "powershell.exe");
            process = new ProcessBuilder(powershell.toString(), "-NoLogo", "-NoProfile", "-NonInteractive",
                    "-OutputFormat", "Text", "-EncodedCommand", encoded)
                    .redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                throw new IOException("Windows display-driver discovery timed out");
            }
            String text = Files.readString(output, StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new IOException("Windows display-driver discovery failed: " + text.trim());
            }
            for (String line : text.lines().filter(s -> !s.isBlank()).distinct().toList()) {
                manifests.addAll(manifestsForLibrary(Path.of(line.trim())));
            }
            if (!manifests.isEmpty()) {
                LOG.info("Adding unregistered Vulkan driver manifests for sd.cpp: {}", manifests);
            }
            cached = List.copyOf(manifests);
        } catch (IOException | InvalidPathException e) {
            LOG.warn("Could not recover unregistered Vulkan drivers; using normal Vulkan discovery. "
                    + "Repair the GPU driver installation if a GPU is missing.", e);
            cached = List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Cancelled Vulkan driver discovery");
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            if (output != null) {
                try {
                    Files.deleteIfExists(output);
                } catch (IOException e) {
                    LOG.warn("Could not remove Vulkan discovery output {}", output, e);
                }
            }
        }
        return cached;
    }

    static List<Path> manifestsForLibrary(Path library) throws IOException {
        if (!library.isAbsolute() || !Files.isRegularFile(library)) {
            return List.of();
        }
        List<Path> matches = new ArrayList<>();
        ObjectMapper mapper = new ObjectMapper();
        try (var files = Files.newDirectoryStream(library.getParent(), "*.json")) {
            for (Path manifest : files) {
                try {
                    String path = mapper.readTree(manifest.toFile()).path("ICD").path("library_path").asText("");
                    // Only load the already registered driver DLL, not unrelated JSON, 32-bit or Vulkan SC drivers.
                    if (!path.isBlank()
                            && Files.isSameFile(library, manifest.getParent().resolve(path).normalize())) {
                        matches.add(manifest);
                    }
                } catch (IOException | InvalidPathException e) {
                    LOG.warn("Ignoring unusable Vulkan manifest candidate {}", manifest, e);
                }
            }
        }
        return matches;
    }
}
