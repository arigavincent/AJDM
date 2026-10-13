package io.ajdm.model;

/** Received includes retried bytes. Written is accepted coverage. Durable is flushed coverage. */
public record ProgressSnapshot(DownloadState state, long receivedBytes, long writtenBytes,
                               long durableBytes, long totalBytes) {}
