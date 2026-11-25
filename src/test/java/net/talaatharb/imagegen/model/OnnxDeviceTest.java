package net.talaatharb.imagegen.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;

class OnnxDeviceTest {
    @Test
    void directMlUsesSavedIndexUnlessSystemPropertyOverridesIt() {
        String old = System.getProperty(Onnx.GPU_DEVICE_PROPERTY);
        String saved = GpuRuntime.directMlDevice();
        try {
            System.clearProperty(Onnx.GPU_DEVICE_PROPERTY);
            GpuRuntime.setDirectMlDevice("");
            assertEquals(Map.of("performance_preference", "high_performance", "device_filter", "gpu"),
                    Onnx.directMlOptions());
            GpuRuntime.setDirectMlDevice("1");
            assertEquals(Map.of("device_id", "1"), Onnx.directMlOptions());
            System.setProperty(Onnx.GPU_DEVICE_PROPERTY, "0");
            assertEquals(Map.of("device_id", "0"), Onnx.directMlOptions());
        } finally {
            GpuRuntime.setDirectMlDevice(saved);
            if (old == null) {
                System.clearProperty(Onnx.GPU_DEVICE_PROPERTY);
            } else {
                System.setProperty(Onnx.GPU_DEVICE_PROPERTY, old);
            }
        }
    }
}
