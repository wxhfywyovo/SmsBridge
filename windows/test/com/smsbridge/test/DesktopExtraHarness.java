package com.smsbridge.test;

import com.smsbridge.pc.AutoStart;
import com.smsbridge.pc.BridgeService;
import com.smsbridge.pc.Config;
import com.smsbridge.pc.Discovery;
import com.smsbridge.pc.HistoryStore;
import com.smsbridge.pc.Json;
import com.smsbridge.pc.WsClient;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 覆盖自动启动、UDP 发现、整条短信复制等边角功能。 */
public final class DesktopExtraHarness {
    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("smsbridge-extra-");
        Config config = new Config(home);
        config.setPort(47995);
        config.setPin("112233");
        HistoryStore history = new HistoryStore(config.historyFile());
        BridgeService service = new BridgeService(config, history);
        service.start();
        Thread.sleep(200);

        // UDP discovery
        DatagramSocket udp = new DatagramSocket(null);
        try {
            udp.setReuseAddress(true);
            udp.bind(new java.net.InetSocketAddress(Discovery.DEFAULT_PORT));
            udp.setSoTimeout(6000);
            byte[] buffer = new byte[2048];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            boolean seen = false;
            long deadline = System.currentTimeMillis() + 6000;
            while (System.currentTimeMillis() < deadline) {
                try {
                    udp.receive(packet);
                } catch (java.net.SocketTimeoutException e) {
                    break;
                }
                String text = new String(packet.getData(), 0, packet.getLength(),
                        StandardCharsets.UTF_8);
                if (text.startsWith(Discovery.PREFIX)) {
                    Json json = Json.parse(text.substring(Discovery.PREFIX.length()));
                    seen = "smsbridge".equals(json.getString("app", ""))
                            && json.getLong("port", 0) == 47995;
                    if (seen) {
                        break;
                    }
                }
            }
            check("桌面端 UDP 广播可被发现", seen);
        } finally {
            udp.close();
        }

        // Auto start registry
        if (AutoStart.supported()) {
            AutoStart.set(true);
            boolean enabled = AutoStart.isEnabled();
            AutoStart.set(false);
            boolean disabled = !AutoStart.isEnabled();
            check("开机自启写入与删除", enabled && disabled);
        }

        // Full-message mode with a message that has no code
        config.setCopyMode(Config.CopyMode.MESSAGE);
        WsClient client = WsClient.connect("127.0.0.1", 47995, 4000);
        client.sendText(Json.obj().put("type", "pair").put("deviceId", "extra")
                .put("deviceName", "Extra").put("pin", "112233").toJson());
        Json pair = Json.parse(client.readText());
        check("额外测试配对", "pair_ok".equals(pair.getString("type", "")));
        String token = pair.getString("token", "");

        String body = "您的快递已放在 3 号柜，取件码 8842。";
        client.sendText(Json.obj().put("type", "sms").put("deviceId", "extra")
                .put("from", "丰巢").put("body", body)
                .put("ts", System.currentTimeMillis()).put("id", "extra-1").toJson());
        Json ack = Json.parse(client.readText());
        check("无验证码短信的全文复制", ack.getBool("copied", false)
                && body.equals(readClipboard()));

        // Invalid token is rejected
        WsClient bad = WsClient.connect("127.0.0.1", 47995, 4000);
        bad.sendText(Json.obj().put("type", "auth").put("deviceId", "extra")
                .put("token", "deadbeef").toJson());
        Json authErr = Json.parse(bad.readText());
        check("错误令牌被拒绝", "auth_err".equals(authErr.getString("type", "")));
        bad.close();

        // Port conflict is reported instead of crashing
        BridgeService second = new BridgeService(config, history);
        second.start();
        check("端口占用有错误提示", !second.isRunning()
                && second.lastError().contains("占用"));
        second.stop();

        client.close();
        service.stop();

        // Config survives reload
        Config reloaded = new Config(home);
        check("PIN 持久化", "112233".equals(reloaded.pin()));
        check("配对设备持久化", reloaded.findDevice("extra") != null
                && reloaded.findDevice("extra").token().equals(token));

        System.out.println("通过 " + passed + " 项，失败 " + failed + " 项");
        System.exit(failed == 0 ? 0 : 1);
    }

    private static String readClipboard() {
        try {
            return (String) Toolkit.getDefaultToolkit().getSystemClipboard()
                    .getData(DataFlavor.stringFlavor);
        } catch (Exception e) {
            return "";
        }
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("[PASS] " + name);
        } else {
            failed++;
            System.out.println("[FAIL] " + name);
        }
    }
}
