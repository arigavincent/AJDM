package io.ajdm.bench;

import java.util.Arrays;

/** Linear-interpolated sample percentiles, explicitly defined for small exploratory samples. */
public final class BenchmarkStatistics {
    private BenchmarkStatistics() {}
    public static double percentile(double[] samples, double quantile) {
        if (samples.length == 0 || !Double.isFinite(quantile) || quantile < 0 || quantile > 1
                || Arrays.stream(samples).anyMatch(value -> !Double.isFinite(value))) {
            throw new IllegalArgumentException("Finite samples and a quantile between zero and one are required");
        }
        double[] sorted = samples.clone(); Arrays.sort(sorted);
        double rank = (sorted.length - 1) * quantile;
        int lower = (int) Math.floor(rank), upper = (int) Math.ceil(rank);
        return sorted[lower] * (1 - (rank - lower)) + sorted[upper] * (rank - lower);
    }
}
