package com.smsbridge.pc;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/** 局域网地址工具。 */
public final class NetUtil {
    private NetUtil() {
    }

    public record Address(String ip, String iface, boolean siteLocal) {
    }

    public static List<Address> ipv4Addresses() {
        List<Address> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) {
                    continue;
                }
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress address = ia.getAddress();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()
                            && !address.isLinkLocalAddress()) {
                        result.add(new Address(address.getHostAddress(), ni.getDisplayName(),
                                address.isSiteLocalAddress()));
                    }
                }
            }
        } catch (SocketException ignored) {
            // 返回已经找到的地址
        }
        return result;
    }

    public static String primaryIp() {
        List<Address> all = ipv4Addresses();
        for (Address a : all) {
            if (a.siteLocal()) {
                return a.ip();
            }
        }
        return all.isEmpty() ? "127.0.0.1" : all.get(0).ip();
    }

    public static List<InetAddress> broadcastAddresses() {
        List<InetAddress> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress broadcast = ia.getBroadcast();
                    if (broadcast != null) {
                        result.add(broadcast);
                    }
                }
            }
        } catch (SocketException ignored) {
            // 忽略
        }
        return result;
    }
}
