package io.ajdm.model;

import java.nio.file.Path;
import java.time.Duration;

/** sha256 is computed when requested or needed for move recovery; only a supplied expected hash verifies source content. */
public record TransferResult(Path target, long bytes, String sha256, Duration elapsed) {}
