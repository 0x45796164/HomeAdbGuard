package app.homeadbguard;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;

import java.net.Inet4Address;
import java.net.InetAddress;

/**
 * Reads the device's local non-loopback IPv4 via ConnectivityManager.
 *
 * Why not java.net.NetworkInterface: modern Android's SELinux policy blocks
 * the underlying getifaddrs() syscall for unprivileged apps, so
 * {@code NetworkInterface.getNetworkInterfaces()} silently fails with
 * "socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC) failed in ifaddrs: Operation
 * not permitted". {@link ConnectivityManager#getLinkProperties(Network)} is
 * the documented replacement and works under our existing
 * {@code ACCESS_NETWORK_STATE} permission.
 */
final class LocalIp {
    private LocalIp() {
    }

    static String firstUsableIpv4(Context ctx) {
        ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
        if (cm == null) return null;
        Network active = cm.getActiveNetwork();
        if (active == null) return null;
        LinkProperties lp;
        try {
            lp = cm.getLinkProperties(active);
        } catch (RuntimeException e) {
            return null;
        }
        if (lp == null) return null;
        for (LinkAddress la : lp.getLinkAddresses()) {
            InetAddress addr = la == null ? null : la.getAddress();
            if (addr == null || addr.isLoopbackAddress() || addr.isLinkLocalAddress()) continue;
            if (addr instanceof Inet4Address) {
                return addr.getHostAddress();
            }
        }
        return null;
    }
}
