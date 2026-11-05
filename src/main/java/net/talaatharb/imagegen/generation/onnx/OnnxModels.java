package net.talaatharb.imagegen.generation.onnx;

import java.util.List;

import net.talaatharb.imagegen.service.GenerationDefaults;

/** Models run in-process by ONNX Runtime. */
public final class OnnxModels {
    private static final long MB = 1L << 20;

    public static final OnnxModel LCM_DREAMSHAPER = new OnnxModel("lcm-dreamshaper-v7-onnx",
            "LCM Dreamshaper v7 (ONNX Runtime)",
            "Latent Consistency distillation of Dreamshaper v7 (SD 1.5) run in-process by ONNX Runtime; 4 steps and "
                    + "DirectML GPU support on Windows. 4.1 GB, MIT.",
            "TheyCallMeHex/LCM-Dreamshaper-V7-ONNX", OnnxModel.standardFiles(true, true), OnnxModel.Sampler.LCM,
            4_100 * MB,
            new GenerationDefaults(512, 512, 4, 8, 8f, true, false,
                    List.of(256, 320, 384, 448, 512, 576, 640, 704, 768)));

    private OnnxModels() {
    }

    public static List<OnnxModel> all() {
        return List.of(LCM_DREAMSHAPER);
    }
}
