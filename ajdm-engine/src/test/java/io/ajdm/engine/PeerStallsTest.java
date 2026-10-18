package io.ajdm.engine;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class PeerStallsTest {
    private static final long S = 1_000_000_000L;

    @Test void aConnectionSilentAloneIsAbandonedOnlyAfterTheTimeout() {
        var peers = new PeerStalls(Duration.ofSeconds(5));
        Object silent = new Object(), a = new Object(), b = new Object();
        for (Object c : new Object[]{silent, a, b}) peers.open(c, 0);
        peers.received(a, 9 * S); peers.received(b, 9 * S);
        assertFalse(peers.abandon(silent, 6 * S, 10 * S), "Silent for only 4 s");
        assertTrue(peers.abandon(silent, 4 * S, 10 * S));
    }

    @Test void whenMostConnectionsGoQuietTogetherNoneIsAbandoned() {
        var peers = new PeerStalls(Duration.ofSeconds(5));
        Object[] connections = new Object[6];
        for (int i = 0; i < connections.length; i++) { connections[i] = new Object(); peers.open(connections[i], 0); }
        // Only two of the five others still receive: the link is degraded, so nobody reconnects.
        peers.received(connections[1], 9 * S); peers.received(connections[2], 9 * S);
        assertFalse(peers.abandon(connections[0], 0, 10 * S));
        peers.received(connections[3], 9 * S);
        assertTrue(peers.abandon(connections[0], 0, 10 * S), "Three of five others moving is a majority");
    }

    @Test void abandonmentsAreSpacedSoAStormCannotForm() {
        var peers = new PeerStalls(Duration.ofSeconds(5));
        Object[] connections = new Object[8];
        for (int i = 0; i < connections.length; i++) { connections[i] = new Object(); peers.open(connections[i], 0); }
        for (int i = 2; i < connections.length; i++) peers.received(connections[i], 19 * S);
        assertTrue(peers.abandon(connections[0], 0, 20 * S));
        assertFalse(peers.abandon(connections[1], 0, 20 * S + S / 2), "A second abandonment within a second");
        assertTrue(peers.abandon(connections[1], 0, 21 * S));
    }

    @Test void aLoneConnectionHasNoPeersToJudgeIt() {
        var peers = new PeerStalls(Duration.ofSeconds(5));
        Object only = new Object(), gone = new Object();
        peers.open(only, 0); peers.open(gone, 0); peers.close(gone);
        assertFalse(peers.abandon(only, 0, 10 * S), "A peer that closed without receiving after the silence proves nothing");
        var alone = new PeerStalls(Duration.ofSeconds(5));
        alone.open(only, 0);
        assertFalse(alone.abandon(only, 0, 10 * S));
    }

    @Test void peersThatFinishedAfterTheSilenceBeganStillProveTheLinkWorks() {
        // The end of a download: the other lanes delivered their last bytes and closed while this one hung.
        var peers = new PeerStalls(Duration.ofSeconds(5));
        Object silent = new Object(), finished = new Object(), earlier = new Object();
        peers.open(silent, 0); peers.open(finished, 0);
        peers.received(finished, 2 * S); peers.close(finished);
        assertTrue(peers.abandon(silent, S, 8 * S));
        var before = new PeerStalls(Duration.ofSeconds(5));
        before.open(silent, 0); before.open(earlier, 0);
        before.received(earlier, S / 2); before.close(earlier);
        assertFalse(before.abandon(silent, S, 8 * S), "Bytes before the silence began say nothing about the link now");
    }
}
