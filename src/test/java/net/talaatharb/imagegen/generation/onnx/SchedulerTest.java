package net.talaatharb.imagegen.generation.onnx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

class SchedulerTest {
    private final SchedulerConfig sd = SchedulerConfig.stableDiffusion();

    @Test
    void lcmTimestepsMatchDiffusers() {
        assertArrayEquals(new int[] {999, 759, 499, 259}, new LcmScheduler(sd, 4).timesteps());
        assertArrayEquals(new int[] {999}, new LcmScheduler(sd, 1).timesteps());
        assertArrayEquals(new int[] {999, 499}, new LcmScheduler(sd, 2).timesteps());
    }

    @Test
    void lcmLastStepReturnsTheDenoisedSample() {
        LcmScheduler scheduler = new LcmScheduler(sd, 1);
        float[] sample = {1f, -1f};
        float[] out = scheduler.step(new float[] {0f, 0f}, 0, sample, new Random(1));
        assertArrayEquals(out, scheduler.denoised());
        // With zero predicted noise the clean estimate is the sample divided by sqrt(alpha_cumprod).
        double alpha = sd.alphasCumprod()[999];
        assertEquals(1 / Math.sqrt(alpha), out[0], 1e-2);
    }

    @Test
    void guidanceEmbeddingIsSinusoidal() {
        float[] embedding = LcmScheduler.guidanceEmbedding(1f, 256);
        assertEquals(256, embedding.length);
        // w = 0: sines are 0 and cosines 1.
        assertEquals(0f, embedding[0], 1e-6);
        assertEquals(1f, embedding[128], 1e-6);
    }

    @Test
    void eulerTimestepSpacings() {
        assertArrayEquals(new int[] {751, 501, 251, 1}, new EulerAncestralScheduler(sd, 4).timesteps());
        SchedulerConfig trailing = new SchedulerConfig(1000, 0.00085, 0.012, "scaled_linear", "trailing", 0, 50,
                false, 10, false);
        assertArrayEquals(new int[] {999}, new EulerAncestralScheduler(trailing, 1).timesteps());
        assertArrayEquals(new int[] {999, 749, 499, 249}, new EulerAncestralScheduler(trailing, 4).timesteps());
    }

    @Test
    void eulerScalesInputsAndReachesZeroNoise() {
        SchedulerConfig trailing = new SchedulerConfig(1000, 0.00085, 0.012, "scaled_linear", "trailing", 0, 50,
                false, 10, false);
        EulerAncestralScheduler scheduler = new EulerAncestralScheduler(trailing, 2);
        double sigma = scheduler.sigma(0);
        assertTrue(sigma > 14 && sigma < 15, "sigma max of SD is about 14.6 but was " + sigma);
        assertEquals((float) sigma, scheduler.initNoiseSigma(), 1e-3);
        assertEquals((float) Math.sqrt(sigma(sd, 751) * sigma(sd, 751) + 1),
                new EulerAncestralScheduler(sd, 4).initNoiseSigma(), 1e-3);
        float[] scaled = scheduler.scaleModelInput(new float[] {2f}, 0);
        assertEquals(2 / Math.sqrt(sigma * sigma + 1), scaled[0], 1e-4);
        // The final step lands exactly on the predicted clean sample.
        float[] last = scheduler.step(new float[] {0.5f}, 1, new float[] {1f}, new Random(1));
        assertEquals(1f - scheduler.sigma(1) * 0.5f, last[0], 1e-4);
    }

    private static double sigma(SchedulerConfig config, int timestep) {
        double alpha = config.alphasCumprod()[timestep];
        return Math.sqrt((1 - alpha) / alpha);
    }

    @Test
    void alphasDecrease() {
        double[] alphas = sd.alphasCumprod();
        assertEquals(1000, alphas.length);
        assertEquals(1 - 0.00085, alphas[0], 1e-9);
        assertTrue(alphas[999] < 0.01);
    }
}
