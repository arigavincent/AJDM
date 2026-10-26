package io.ajdm.engine;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared view of one download's response bodies, so a silent connection is abandoned only when the silence is its
 * own. A connection qualifies after {@code timeout} without bytes when most of its peers prove the link works: an
 * open peer counts if it received bytes within the timeout, and a peer that has since closed counts if it received
 * bytes after this connection went quiet (it finished its work rather than stalled). The download abandons at most
 * one connection per second. When most connections go quiet together the link is degraded, not the flows;
 * reconnecting them all at once (a storm seen on a real mobile link, where the reconnects then timed out) is worse
 * than waiting.
 */
final class PeerStalls {
    private static final long SPACING_NANOS = 1_000_000_000L, FORGET_NANOS = 60_000_000_000L;
    private final long timeoutNanos;
    private final Map<Object, Peer> peers = new ConcurrentHashMap<>();
    private boolean abandoned;
    private long lastAbandon;

    private static final class Peer {
        volatile long lastByte;
        volatile boolean closed;
        Peer(long now) { lastByte = now; }
    }

    PeerStalls(java.time.Duration timeout) { timeoutNanos = timeout.toNanos(); }

    long timeoutNanos() { return timeoutNanos; }
    void open(Object connection, long now) { peers.put(connection, new Peer(now)); }
    void close(Object connection) {
        Peer peer = peers.get(connection);
        if (peer != null) peer.closed = true;
    }
    void received(Object connection, long now) {
        Peer peer = peers.get(connection);
        if (peer != null) peer.lastByte = now;
    }

    /** Whether {@code connection}, silent since {@code since}, should be abandoned now; claims the one-per-second slot. */
    synchronized boolean abandon(Object connection, long since, long now) {
        if (now - since < timeoutNanos) return false;
        int others = 0, moving = 0;
        for (var entry : peers.entrySet()) {
            Peer peer = entry.getValue();
            if (peer.closed && now - peer.lastByte > FORGET_NANOS) { peers.remove(entry.getKey(), peer); continue; }
            if (entry.getKey() == connection) continue;
            if (peer.closed) {
                if (peer.lastByte - since > 0) { others++; moving++; }
            } else {
                others++;
                if (now - peer.lastByte < timeoutNanos) moving++;
            }
        }
        if (others == 0 || moving * 2 <= others) return false;
        if (abandoned && now - lastAbandon < SPACING_NANOS) return false;
        abandoned = true; lastAbandon = now;
        return true;
    }
}
