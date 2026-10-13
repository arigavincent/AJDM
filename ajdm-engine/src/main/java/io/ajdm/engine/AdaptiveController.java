package io.ajdm.engine;

/** Settled throughput trials; observations never change ownership of active ranges. */
final class AdaptiveController {
    private static final long SECOND = 1_000_000_000L;
    private enum Phase { BASELINE, SETTLE, TRIAL, HOLD }
    private final int maximum;
    private int target, baselineTarget;
    private long phaseStart, phaseBytes, lastBytes, lastTime, cooldownUntil;
    private double baselineRate, heldRate, peakRate, bestLaneRate;
    private long pressureSince = Long.MIN_VALUE, changeSince = Long.MIN_VALUE;
    private boolean slowStart = true, learned, measuredGrowth;
    private final double learnedRate;
    private Phase phase = Phase.BASELINE;

    AdaptiveController(int maximum, long now) { this(maximum, Math.min(4, maximum), now, 0); }
    AdaptiveController(int maximum, int initial, long now, double learnedRate) {
        if (maximum < 1 || maximum > 32 || initial < 1 || initial > maximum) throw new IllegalArgumentException("Invalid adaptive bounds");
        this.maximum = maximum; target = initial; phaseStart = lastTime = now;
        this.learnedRate = learnedRate; learned = learnedRate > 0;
    }
    int target() { return target; }
    boolean settled() { return phase == Phase.BASELINE || phase == Phase.HOLD; }
    void lanes(double[] rates, boolean[] busy) {
        for (int i = 0; i < rates.length; i++) if (busy[i]) bestLaneRate = Math.max(bestLaneRate, rates[i]);
    }
    Decision observe(long now, long bytes, long remaining, boolean backpressure, int pushbacks, boolean canSplit) {
        if (now <= lastTime) return null;
        double rate = Math.max(0, bytes - lastBytes) * (double) SECOND / (now - lastTime);
        lastTime = now; lastBytes = bytes;
        if (pushbacks > 0) {
            target = Math.max(1, target / 2); slowStart = false;
            hold(now, bytes, rate); return new Decision(target, "server-pushback", rate);
        }
        if (backpressure) {
            if (pressureSince == Long.MIN_VALUE) pressureSince = now;
            if (now - pressureSince < SECOND / 2) return null;
            boolean trial = phase == Phase.SETTLE || phase == Phase.TRIAL;
            if (trial) target = baselineTarget;
            hold(now, bytes, Math.max(rate, baselineRate));
            return trial ? new Decision(target, "writer-pressure-rollback", rate) : null;
        }
        pressureSince = Long.MIN_VALUE;
        long age = now - phaseStart;
        double average = age > 0 ? Math.max(0, bytes - phaseBytes) * (double) SECOND / age : 0;
        switch (phase) {
            case BASELINE -> {
                if (age < 2 * SECOND) return null;
                baselineRate = average; peakRate = Math.max(peakRate, average);
                if (learned) {
                    learned = false;
                    if (average < learnedRate * .65 || average > learnedRate * 1.5) {
                        target = Math.min(4, maximum); resetHistory(); begin(Phase.BASELINE, now, bytes);
                        return new Decision(target, "host-hint-revalidate", average);
                    }
                }
                boolean linkBound = measuredGrowth && bestLaneRate > 0 && average < .80 * target * bestLaneRate && average >= .90 * peakRate;
                if (!linkBound && eligible(remaining, canSplit, baselineRate) && target < maximum) {
                    baselineTarget = target; target = Math.min(maximum, slowStart ? target * 2 : target + 1);
                    begin(Phase.SETTLE, now, bytes); return new Decision(target, "trial-start", baselineRate);
                }
                if (linkBound) { hold(now, bytes, baselineRate); return new Decision(target, "link-bound-hold", baselineRate); }
                begin(Phase.BASELINE, now, bytes);
            }
            case SETTLE -> { if (age >= SECOND) begin(Phase.TRIAL, now, bytes); }
            case TRIAL -> {
                if (age < 2 * SECOND) return null;
                measuredGrowth = true;
                peakRate = Math.max(peakRate, average);
                if (baselineRate > 0 && average >= baselineRate * (slowStart ? 1.15 : 1.05)) {
                    baselineRate = average; begin(Phase.BASELINE, now, bytes);
                    return new Decision(target, "trial-accepted", average);
                }
                boolean wasSlow = slowStart; slowStart = false;
                target = average <= baselineRate * .90 ? Math.max(1, baselineTarget - 1) : baselineTarget;
                boolean plateau = average >= baselineRate * .90 && average <= baselineRate * 1.05;
                if (wasSlow && !plateau && target < maximum && eligible(remaining, canSplit, average)) begin(Phase.BASELINE, now, bytes);
                else hold(now, bytes, baselineRate);
                return new Decision(target, "trial-rollback", average);
            }
            case HOLD -> {
                if (now < cooldownUntil) return null;
                boolean changed = rate > 0 && (heldRate == 0 || Math.abs(rate / heldRate - 1) >= .25);
                if (changed) {
                    if (changeSince == Long.MIN_VALUE) changeSince = now;
                    if (now - changeSince >= 2 * SECOND) {
                        resetHistory(); begin(Phase.BASELINE, now, bytes);
                        return new Decision(target, "network-change", rate);
                    }
                } else changeSince = Long.MIN_VALUE;
            }
        }
        return null;
    }
    private boolean eligible(long remaining, boolean canSplit, double rate) { return canSplit && rate > 0 && remaining / rate >= 6; }
    private void resetHistory() { peakRate = bestLaneRate = 0; changeSince = Long.MIN_VALUE; slowStart = true; measuredGrowth = false; }
    private void hold(long now, long bytes, double rate) { heldRate = rate; changeSince = Long.MIN_VALUE; cooldownUntil = now + 10 * SECOND; begin(Phase.HOLD, now, bytes); }
    private void begin(Phase next, long now, long bytes) { phase = next; phaseStart = now; phaseBytes = bytes; }
    record Decision(int target, String reason, double usefulBytesPerSecond) { }
}
