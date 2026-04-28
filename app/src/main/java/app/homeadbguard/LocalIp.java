package app.homeadbguard;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/**
 * Reads the device's local non-loopback IPv4 address via java.net.NetworkInterface.
 * No new permissions required; results are display-only and never leave the device.
 */
final class LocalIp {
    private LocalIp() {
    }

    static String firstUsableIpv4() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (ni == null || !ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr == null || addr.isLoopbackAddress() || addr.isLinkLocalAddress()) continue;
                    if (addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
