package net.talaatharb.imagegen.generation.onnx;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The parts of a diffusers {@code scheduler/scheduler_config.json} needed by the schedulers here.
 *
 * @param trainTimesteps   {@code num_train_timesteps}
 * @param betaStart        {@code beta_start}
 * @param betaEnd          {@code beta_end}
 * @param betaSchedule     {@code linear} or {@code scaled_linear}
 * @param timestepSpacing  {@code leading}, {@code trailing} or {@code linspace}
 * @param stepsOffset      {@code steps_offset}
 * @param originalSteps    LCM's {@code original_inference_steps}
 * @param setAlphaToOne    {@code set_alpha_to_one}
 * @param timestepScaling  LCM's {@code timestep_scaling}
 * @param vPrediction      true for {@code prediction_type = v_prediction}
 */
public record SchedulerConfig(int trainTimesteps, double betaStart, double betaEnd, String betaSchedule,
        String timestepSpacing, int stepsOffset, int originalSteps, boolean setAlphaToOne, double timestepScaling,
        boolean vPrediction) {

    /** Stable Diffusion 1.x defaults. */
    public static SchedulerConfig stableDiffusion() {
        return new SchedulerConfig(1000, 0.00085, 0.012, "scaled_linear", "leading", 1, 50, false, 10.0, false);
    }

    public static SchedulerConfig load(Path file) throws IOException {
        SchedulerConfig d = stableDiffusion();
        if (!Files.isRegularFile(file)) {
            return d;
        }
        JsonNode n = new ObjectMapper().readTree(file.toFile());
        return new SchedulerConfig(
                n.path("num_train_timesteps").asInt(d.trainTimesteps()),
                n.path("beta_start").asDouble(d.betaStart()),
                n.path("beta_end").asDouble(d.betaEnd()),
                n.path("beta_schedule").asText(d.betaSchedule()),
                n.path("timestep_spacing").asText(d.timestepSpacing()),
                n.path("steps_offset").asInt(d.stepsOffset()),
                n.path("original_inference_steps").asInt(d.originalSteps()),
                n.path("set_alpha_to_one").asBoolean(d.setAlphaToOne()),
                n.path("timestep_scaling").asDouble(d.timestepScaling()),
                "v_prediction".equals(n.path("prediction_type").asText("epsilon")));
    }

    /** Cumulative products of {@code 1 - beta} for every training timestep. */
    public double[] alphasCumprod() {
        double[] result = new double[trainTimesteps];
        double product = 1;
        for (int i = 0; i < trainTimesteps; i++) {
            double t = trainTimesteps == 1 ? 0 : i / (double) (trainTimesteps - 1);
            double beta;
            if ("scaled_linear".equals(betaSchedule)) {
                double root = Math.sqrt(betaStart) + t * (Math.sqrt(betaEnd) - Math.sqrt(betaStart));
                beta = root * root;
            } else {
                beta = betaStart + t * (betaEnd - betaStart);
            }
            product *= 1 - beta;
            result[i] = product;
        }
        return result;
    }
}
