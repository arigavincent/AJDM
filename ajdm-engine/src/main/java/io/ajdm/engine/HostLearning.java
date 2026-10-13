package io.ajdm.engine;

import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.ArrayList;

/** Hashes origin and local network identity; no URLs, interface names or IPs are stored. */
final class HostLearning {
    private HostLearning() {}
    static String key(URI uri) {
        if (Boolean.getBoolean("ajdm.hostLearning.disabled")) return null;
        try {
            var addresses = new ArrayList<String>();
            var interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return null;
            for (var nic : Collections.list(interfaces)) {
                if (!nic.isUp() || nic.isLoopback()) continue;
                for (var address : Collections.list(nic.getInetAddresses())) addresses.add(address.getHostAddress());
            }
            if (addresses.isEmpty()) return null;
            addresses.sort(String::compareTo);
            String origin = uri.getScheme().toLowerCase(java.util.Locale.ROOT) + "://" + uri.getHost().toLowerCase(java.util.Locale.ROOT)
                    + ":" + (uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    (origin + "\n" + String.join("\n", addresses)).getBytes(StandardCharsets.UTF_8)));
        } catch (SocketException | NoSuchAlgorithmException | SecurityException e) { return null; }
    }
}
