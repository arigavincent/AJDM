package io.ajdm.bench;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BenchmarkStatisticsTest {
    @Test void percentilesInterpolateAndDoNotModifyRawRunOrder() {
        double[] samples = {4, 1, 3, 2};
        assertEquals(2.5, BenchmarkStatistics.percentile(samples, .5));
        assertEquals(1.3, BenchmarkStatistics.percentile(samples, .1), 1e-12);
        assertEquals(3.7, BenchmarkStatistics.percentile(samples, .9), 1e-12);
        assertArrayEquals(new double[]{4, 1, 3, 2}, samples);
    }
    @Test void oneSampleAndEndpointsAreWellDefined() {
        assertEquals(7, BenchmarkStatistics.percentile(new double[]{7}, .9));
        assertEquals(1, BenchmarkStatistics.percentile(new double[]{3, 1}, 0));
        assertEquals(3, BenchmarkStatistics.percentile(new double[]{3, 1}, 1));
    }
    @Test void missingInvalidOrNonFiniteValuesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> BenchmarkStatistics.percentile(new double[0], .5));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkStatistics.percentile(new double[]{1}, -1));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkStatistics.percentile(new double[]{1}, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkStatistics.percentile(new double[]{Double.NaN}, .5));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkStatistics.percentile(new double[]{Double.POSITIVE_INFINITY}, .5));
    }
}
