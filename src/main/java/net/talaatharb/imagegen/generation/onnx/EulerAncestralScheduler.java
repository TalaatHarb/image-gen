package net.talaatharb.imagegen.generation.onnx;

import java.util.Random;

/**
 * Euler ancestral sampler (a port of diffusers' {@code EulerAncestralDiscreteScheduler}); works for regular
 * Stable Diffusion checkpoints and for few-step models such as SD-Turbo ({@code timestep_spacing = trailing}).
 */
public final class EulerAncestralScheduler implements Scheduler {
    private final SchedulerConfig config;
    private final int[] timesteps;
    private final double[] sigmas;
    private float[] denoised;

    public EulerAncestralScheduler(SchedulerConfig config, int steps) {
        this.config = config;
        this.timesteps = timesteps(config, steps);
        double[] alphas = config.alphasCumprod();
        this.sigmas = new double[steps + 1];
        for (int i = 0; i < steps; i++) {
            double alpha = alphas[timesteps[i]];
            sigmas[i] = Math.sqrt((1 - alpha) / alpha);
        }
        sigmas[steps] = 0;
    }

    static int[] timesteps(SchedulerConfig config, int steps) {
        int n = config.trainTimesteps();
        int[] result = new int[steps];
        switch (config.timestepSpacing()) {
            case "trailing" -> {
                double ratio = n / (double) steps;
                for (int i = 0; i < steps; i++) {
                    result[i] = (int) Math.round(n - i * ratio) - 1;
                }
            }
            case "linspace" -> {
                for (int i = 0; i < steps; i++) {
                    double value = steps == 1 ? n - 1 : (n - 1) * (steps - 1 - i) / (double) (steps - 1);
                    result[i] = (int) Math.round(value);
                }
            }
            default -> {
                int ratio = n / steps;
                for (int i = 0; i < steps; i++) {
                    result[i] = Math.min(n - 1, (steps - 1 - i) * ratio + config.stepsOffset());
                }
            }
        }
        return result;
    }

    @Override
    public int[] timesteps() {
        return timesteps.clone();
    }

    double sigma(int index) {
        return sigmas[index];
    }

    @Override
    public float[] denoised() {
        return denoised;
    }

    @Override
    public float initNoiseSigma() {
        double max = sigmas[0];
        String spacing = config.timestepSpacing();
        return (float) (spacing.equals("linspace") || spacing.equals("trailing") ? max : Math.sqrt(max * max + 1));
    }

    @Override
    public float[] scaleModelInput(float[] sample, int index) {
        double scale = 1 / Math.sqrt(sigmas[index] * sigmas[index] + 1);
        float[] scaled = new float[sample.length];
        for (int i = 0; i < sample.length; i++) {
            scaled[i] = (float) (sample[i] * scale);
        }
        return scaled;
    }

    @Override
    public float[] step(float[] modelOutput, int index, float[] sample, Random random) {
        double sigma = sigmas[index];
        double sigmaNext = sigmas[index + 1];
        double sigmaUp = Math.sqrt(Math.max(0,
                sigmaNext * sigmaNext * (sigma * sigma - sigmaNext * sigmaNext) / (sigma * sigma)));
        double sigmaDown = Math.sqrt(Math.max(0, sigmaNext * sigmaNext - sigmaUp * sigmaUp));
        double dt = sigmaDown - sigma;
        double vScale = 1 / Math.sqrt(sigma * sigma + 1);
        float[] noise = sigmaUp > 0 ? Scheduler.gaussian(sample.length, random) : null;
        float[] next = new float[sample.length];
        float[] estimate = new float[sample.length];
        for (int i = 0; i < sample.length; i++) {
            double denoised = config.vPrediction()
                    ? modelOutput[i] * (-sigma * vScale) + sample[i] * (vScale * vScale)
                    : sample[i] - sigma * modelOutput[i];
            estimate[i] = (float) denoised;
            double derivative = (sample[i] - denoised) / sigma;
            double value = sample[i] + derivative * dt;
            if (noise != null) {
                value += noise[i] * sigmaUp;
            }
            next[i] = (float) value;
        }
        this.denoised = estimate;
        return next;
    }
}
