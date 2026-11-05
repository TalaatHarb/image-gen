package net.talaatharb.imagegen.generation.sdcpp;

import java.util.Locale;
import java.util.Optional;

/**
 * Picks the prebuilt <a href="https://github.com/leejet/stable-diffusion.cpp">stable-diffusion.cpp</a> release
 * archive for the current OS, CPU architecture and GPU preference.
 *
 * @param variant    short name used for the install directory ({@code cpu}, {@code vulkan} or {@code metal})
 * @param asset      file name of the release archive
 * @param executable name of the command line program inside the archive
 * @param gpu        true when the build runs on the GPU
 */
public record SdCppPlatform(String variant, String asset, String executable, boolean gpu) {
    /** Pinned release; newer releases can change command line flags. */
    public static final String RELEASE = "master-1050-58bb3c1";
    static final String COMMIT = "58bb3c1";
    static final String DOWNLOAD_BASE = "https://github.com/leejet/stable-diffusion.cpp/releases/download/"
            + RELEASE + "/";

    public String url() {
        return DOWNLOAD_BASE + asset;
    }

    /** Detects the build for this machine; {@code gpu} selects the Vulkan build where one is published. */
    public static Optional<SdCppPlatform> current(boolean gpu) {
        return detect(System.getProperty("os.name", ""), System.getProperty("os.arch", ""), gpu);
    }

    static Optional<SdCppPlatform> detect(String osName, String osArch, boolean gpu) {
        String os = osName.toLowerCase(Locale.ROOT);
        String arch = osArch.toLowerCase(Locale.ROOT);
        boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
        boolean arm64 = arch.equals("aarch64") || arch.equals("arm64");
        String prefix = "sd-master-" + COMMIT + "-bin-";
        if (os.startsWith("windows") && x64) {
            return Optional.of(gpu
                    ? new SdCppPlatform("vulkan", prefix + "win-vulkan-x64.zip", "sd-cli.exe", true)
                    : new SdCppPlatform("cpu", prefix + "win-cpu-x64.zip", "sd-cli.exe", false));
        }
        if (os.startsWith("linux") && x64) {
            return Optional.of(gpu
                    ? new SdCppPlatform("vulkan", prefix + "Linux-Ubuntu-26.04-x86_64-vulkan.zip", "sd-cli", true)
                    : new SdCppPlatform("cpu", prefix + "Linux-Ubuntu-26.04-x86_64.zip", "sd-cli", false));
        }
        if ((os.startsWith("mac") || os.startsWith("darwin")) && arm64) {
            // The macOS build always uses Metal.
            return Optional.of(new SdCppPlatform("metal", prefix + "Darwin-macOS-26.6.2-arm64.zip", "sd-cli", true));
        }
        return Optional.empty();
    }

    /** True on platforms where a GPU build can be chosen separately from the CPU build. */
    public static boolean hasSeparateGpuBuild() {
        return current(true).map(gpuBuild -> current(false).map(cpu -> !cpu.equals(gpuBuild)).orElse(false))
                .orElse(false);
    }
}
