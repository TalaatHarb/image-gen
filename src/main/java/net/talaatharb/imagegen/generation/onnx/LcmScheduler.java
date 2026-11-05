package net.talaatharb.imagegen.generation.onnx;

import java.util.Random;

/**
 * Latent Consistency Model multi-step sampler (a port of diffusers' {@code LCMScheduler}): every step predicts the
 * clean latents directly and, except for the last step, re-noises them to the next timestep.
 */
public final class LcmScheduler implements Scheduler {
    private static final double SIGMA_DATA = 0.5;

    private final SchedulerConfig config;
    private final double[] alphasCumprod;
    private final double finalAlphaCumprod;
    private final int[] timesteps;
    private float[] denoised;

    public LcmScheduler(SchedulerConfig config, int steps) {
        this.config = config;
        this.alphasCumprod = config.alphasCumprod();
        this.finalAlphaCumprod = config.setAlphaToOne() ? 1.0 : alphasCumprod[0];
        this.timesteps = timesteps(config, steps);
    }

    static int[] timesteps(SchedulerConfig config, int steps) {
        int original = config.originalSteps();
        if (steps > original) {
            throw new IllegalArgumentException("LCM supports at most " + original + " steps");
        }
        int k = config.trainTimesteps() / original;
        // Training timesteps of the original schedule, noisiest first: 999, 979, ..., 19.
        int[] origin = new int[original];
        for (int i = 0; i < original; i++) {
            origin[i] = (original - i) * k - 1;
        }
        int[] result = new int[steps];
        for (int i = 0; i < steps; i++) {
            result[i] = origin[(int) Math.floor(i * original / (double) steps)];
        }
        return result;
    }

    @Override
    public int[] timesteps() {
        return timesteps.clone();
    }

    @Override
    public float[] denoised() {
        return denoised;
    }

    @Override
    public float initNoiseSigma() {
        return 1f;
    }

    @Override
    public float[] scaleModelInput(float[] sample, int index) {
        return sample;
    }

    @Override
    public float[] step(float[] modelOutput, int index, float[] sample, Random random) {
        int t = timesteps[index];
        boolean last = index == timesteps.length - 1;
        double alpha = alphasCumprod[t];
        double alphaPrev = last ? finalAlphaCumprod : alphasCumprod[timesteps[index + 1]];
        double beta = 1 - alpha;

        double scaled = t * config.timestepScaling();
        double cSkip = SIGMA_DATA * SIGMA_DATA / (scaled * scaled + SIGMA_DATA * SIGMA_DATA);
        double cOut = scaled / Math.sqrt(scaled * scaled + SIGMA_DATA * SIGMA_DATA);

        double sqrtAlpha = Math.sqrt(alpha);
        double sqrtBeta = Math.sqrt(beta);
        float[] denoised = new float[sample.length];
        for (int i = 0; i < sample.length; i++) {
            double predicted = config.vPrediction()
                    ? sqrtAlpha * sample[i] - sqrtBeta * modelOutput[i]
                    : (sample[i] - sqrtBeta * modelOutput[i]) / sqrtAlpha;
            denoised[i] = (float) (cOut * predicted + cSkip * sample[i]);
        }
        this.denoised = denoised;
        if (last) {
            return denoised;
        }
        float[] noise = Scheduler.gaussian(sample.length, random);
        double a = Math.sqrt(alphaPrev);
        double b = Math.sqrt(1 - alphaPrev);
        float[] next = new float[sample.length];
        for (int i = 0; i < sample.length; i++) {
            next[i] = (float) (a * denoised[i] + b * noise[i]);
        }
        return next;
    }

    /**
     * LCM's guidance-scale embedding ({@code timestep_cond}): sinusoidal features of {@code (guidance - 1) * 1000}.
     */
    public static float[] guidanceEmbedding(float guidanceScale, int dim) {
        double w = (guidanceScale - 1) * 1000.0;
        int half = dim / 2;
        double factor = Math.log(10000.0) / (half - 1);
        float[] embedding = new float[dim];
        for (int i = 0; i < half; i++) {
            double value = w * Math.exp(-factor * i);
            embedding[i] = (float) Math.sin(value);
            embedding[half + i] = (float) Math.cos(value);
        }
        return embedding;
    }
}
