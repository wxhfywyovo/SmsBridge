package com.smsbridge.pc;

import java.io.Closeable;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 每 2 秒向局域网广播一次自己的地址，手机端据此免输入 IP。 */
public final class Discovery implements Closeable {
    public static final String PREFIX = "SMSBRIDGE1 ";
    public static final int DEFAULT_PORT = 47824;

    private final Config config;
    private final String pcName;
    private final int discoveryPort;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "smsbridge-discovery");
                t.setDaemon(true);
                return t;
            });
    private volatile DatagramSocket socket;

    public Discovery(Config config, String pcName) {
        this(config, pcName, DEFAULT_PORT);
    }

    public Discovery(Config config, String pcName, int discoveryPort) {
        this.config = config;
        this.pcName = pcName;
        this.discoveryPort = discoveryPort;
    }

    public void start() throws SocketException {
        DatagramSocket ds = new DatagramSocket();
        ds.setBroadcast(true);
        socket = ds;
        scheduler.scheduleAtFixedRate(this::broadcast, 0, 2, TimeUnit.SECONDS);
    }

    private void broadcast() {
        DatagramSocket ds = socket;
        if (ds == null || ds.isClosed()) {
            return;
        }
        try {
            String payload = Json.obj()
                    .put("app", "smsbridge")
                    .put("proto", 1)
                    .put("name", pcName)
                    .put("port", config.port())
                    .put("ip", NetUtil.primaryIp())
                    .put("version", Main.VERSION)
                    .toJson();
            byte[] bytes = (PREFIX + payload).getBytes(StandardCharsets.UTF_8);
            send(ds, bytes, InetAddress.getByName("255.255.255.255"));
            List<InetAddress> broadcasts = NetUtil.broadcastAddresses();
            for (InetAddress address : broadcasts) {
                send(ds, bytes, address);
            }
        } catch (Exception ignored) {
            // 网络切换时偶发失败，下一轮会重试
        }
    }

    private void send(DatagramSocket socket, byte[] data, InetAddress target) {
        try {
            socket.send(new DatagramPacket(data, data.length, target, discoveryPort));
        } catch (Exception ignored) {
            // 忽略单个网卡的失败
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        DatagramSocket ds = socket;
        if (ds != null) {
            ds.close();
        }
    }
}
