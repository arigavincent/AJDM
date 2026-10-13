package io.ajdm.bench;

import io.ajdm.engine.TransferHandle;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Sampled process metrics include the fixture and sampler; peaks are not continuous high-water marks. */
final class TransferSampler {
    private final TransferHandle handle;
    private final BenchmarkServer server;
    private final long started;
    private final Thread worker;
    private final StringBuilder csv = new StringBuilder("elapsed_ms,state,received_bytes,written_bytes,durable_bytes,queued_write_bytes,heap_bytes,rss_bytes,process_cpu_nanos,active_responses,adaptive_target,adaptive_busy\n");
    private volatile boolean stopped;
    private long heapPeak, rssPeak = -1, queuePeak, durabilityLagPeak;
    private int count;
    TransferSampler(TransferHandle handle, BenchmarkServer server, long started) {
        this.handle = handle; this.server = server; this.started = started;
        worker = Thread.ofVirtual().name("ajdm-benchmark-sampler").start(() -> {
            try { while (!stopped) { sample(); TimeUnit.MILLISECONDS.sleep(50); } }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
    }
    static long cpuNanos() {
        var bean = ManagementFactory.getOperatingSystemMXBean();
        return bean instanceof com.sun.management.OperatingSystemMXBean process ? process.getProcessCpuTime() : -1;
    }
    static long gcMillis() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean -> Math.max(0, bean.getCollectionTime())).sum();
    }
    private static long rssBytes() {
        if (!System.getProperty("os.name").equalsIgnoreCase("Linux")) return -1;
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/status"))) if (line.startsWith("VmRSS:")) {
                return Long.parseLong(line.substring(6).strip().split("\\s+")[0]) * 1024;
            }
        } catch (IOException | NumberFormatException ignored) { /* Metric unavailable; never substitute zero. */ }
        return -1;
    }
    private synchronized void sample() {
        var progress = handle.progress(); var adaptation = handle.adaptation();
        long heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        long rss = rssBytes(), queue = handle.queuedWriteBytes();
        heapPeak = Math.max(heapPeak, heap); rssPeak = Math.max(rssPeak, rss); queuePeak = Math.max(queuePeak, queue);
        durabilityLagPeak = Math.max(durabilityLagPeak, Math.max(0, progress.writtenBytes() - progress.durableBytes())); count++;
        csv.append(String.format(Locale.ROOT, "%d,%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d%n",
                (System.nanoTime() - started) / 1_000_000, progress.state(), progress.receivedBytes(), progress.writtenBytes(),
                progress.durableBytes(), queue, heap, rss, cpuNanos(), server == null ? -1 : server.activeResponses(), adaptation.targetConnections(), adaptation.busyWorkers()));
    }
    void finish() throws InterruptedException { stopped = true; worker.interrupt(); worker.join(); sample(); }
    long heapPeak() { return heapPeak; }
    long rssPeak() { return rssPeak; }
    long queuePeak() { return queuePeak; }
    long durabilityLagPeak() { return durabilityLagPeak; }
    int count() { return count; }
    String csv() { return csv.toString(); }
}
