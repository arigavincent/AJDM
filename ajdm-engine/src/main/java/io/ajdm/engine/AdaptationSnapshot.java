package io.ajdm.engine;

import java.util.List;

public record AdaptationSnapshot(int targetConnections, int busyWorkers, long queuedBytes,
                                 List<Double> workerBytesPerSecond, List<AdaptationEvent> decisions) {
    public AdaptationSnapshot {
        workerBytesPerSecond = List.copyOf(workerBytesPerSecond); decisions = List.copyOf(decisions);
    }
    public static AdaptationSnapshot empty() { return new AdaptationSnapshot(0, 0, 0, List.of(), List.of()); }
}
