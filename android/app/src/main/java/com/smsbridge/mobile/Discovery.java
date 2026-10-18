package com.smsbridge.mobile;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 监听电脑每 2 秒发出的 UDP 广播，自动找到电脑 IP。 */
public final class Discovery {
    public static final int PORT = 47824;
    public static final String PREFIX = "SMSBRIDGE1 ";

    public static final class Host {
        public final String name;
        public final String ip;
        public final int port;

        Host(String name, String ip, int port) {
            this.name = name;
            this.ip = ip;
            this.port = port;
        }

        @Override
        public String toString() {
            return name + " @ " + ip + ":" + port;
        }
    }

    private Discovery() {
    }

    public static List<Host> discover(int timeoutMillis) {
        Map<String, Host> found = new LinkedHashMap<>();
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(PORT));
            long deadline = System.currentTimeMillis() + timeoutMillis;
            byte[] buffer = new byte[2048];
            while (System.currentTimeMillis() < deadline) {
                int wait = (int) Math.max(150, deadline - System.currentTimeMillis());
                socket.setSoTimeout(wait);
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException e) {
                    break;
                }
                String text = new String(packet.getData(), 0, packet.getLength(),
                        StandardCharsets.UTF_8);
                if (!text.startsWith(PREFIX)) {
                    continue;
                }
                try {
                    Json json = Json.parse(text.substring(PREFIX.length()));
                    if (!"smsbridge".equals(json.getString("app", ""))) {
                        continue;
                    }
                    String ip = json.getString("ip", "");
                    if (ip.isEmpty() && packet.getAddress() != null) {
                        ip = packet.getAddress().getHostAddress();
                    }
                    int port = (int) json.getLong("port", 47823);
                    String name = json.getString("name", "电脑");
                    if (!ip.isEmpty() && port > 0 && port < 65536) {
                        found.put(ip + ":" + port, new Host(name, ip, port));
                    }
                } catch (RuntimeException ignored) {
                    // 忽略坏包
                }
            }
        } catch (Exception ignored) {
            // 端口被占用或没有网络时返回已找到的结果
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
        return new ArrayList<>(found.values());
    }
}
