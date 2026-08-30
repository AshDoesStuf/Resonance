package me.ash.resonance.util;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

public class NetworkUtils {
  public static String getLocalIpAddress() {
    try {
      List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
      for (NetworkInterface intf : interfaces) {
        List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
        for (InetAddress addr : addrs) {
          if (!addr.isLoopbackAddress()) {
            String sAddr = addr.getHostAddress();
            boolean isIPv4 = sAddr.indexOf(':') < 0;
            if (isIPv4) {
              return sAddr;
            }
          }
        }
      }
    } catch (Exception ex) {
      ex.printStackTrace();
    }
    return "Unknown IP";
  }
}
