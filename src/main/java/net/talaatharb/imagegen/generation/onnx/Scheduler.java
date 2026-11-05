package net.talaatharb.imagegen.generation.onnx;

import java.util.Random;

/** A diffusion sampler working on flat latent arrays. */
public interface Scheduler {
    /** Model timesteps, from noisiest to cleanest. */
    int[] timesteps();

    /** Scale of the initial Gaussian noise. */
    float initNoiseSigma();

    /** Returns the model input for step {@code index} (may return {@code sample} itself). */
    float[] scaleModelInput(float[] sample, int index);

    /**
     * Advances one step.
     *
     * @param modelOutput the (guided) noise prediction for {@code sample}
     * @param random      source of the noise added by stochastic samplers
     * @return the less noisy sample; after the last step, the final latents
     */
    float[] step(float[] modelOutput, int index, float[] sample, Random random);

    /** The model's estimate of the clean latents from the last {@link #step} (used for previews). */
    float[] denoised();

    static float[] gaussian(int length, Random random) {
        float[] noise = new float[length];
        for (int i = 0; i < length; i++) {
            noise[i] = (float) random.nextGaussian();
        }
        return noise;
    }
}
