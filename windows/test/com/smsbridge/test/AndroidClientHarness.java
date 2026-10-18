package com.smsbridge.test;

import com.smsbridge.mobile.Json;
import com.smsbridge.mobile.WsClient;
import com.smsbridge.pc.BridgeService;
import com.smsbridge.pc.Config;
import com.smsbridge.pc.HistoryStore;
import java.nio.file.Files;
import java.nio.file.Path;

/** 用安卓端真实的 WsClient 代码连接桌面服务，验证两端协议兼容。 */
public final class AndroidClientHarness {
    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("smsbridge-android-");
        Config config = new Config(home);
        config.setPort(47993);
        config.setPin("246810");
        HistoryStore history = new HistoryStore(config.historyFile());
        BridgeService service = new BridgeService(config, history);
        service.start();
        Thread.sleep(300);

        int passed = 0;
        int failed = 0;

        WsClient client = WsClient.connect("127.0.0.1", 47993, 4000);
        client.sendText(Json.obj().put("type", "pair").put("deviceId", "android-test")
                .put("deviceName", "Pixel 8").put("pin", "000000").toString());
        Json bad = Json.parse(client.readText());
        if ("pair_err".equals(bad.getString("type", ""))) {
            passed++;
            System.out.println("[PASS] 安卓客户端错误 PIN 被拒绝");
        } else {
            failed++;
            System.out.println("[FAIL] 安卓客户端错误 PIN");
        }

        client.sendText(Json.obj().put("type", "pair").put("deviceId", "android-test")
                .put("deviceName", "Pixel 8").put("pin", "246810").toString());
        Json pair = Json.parse(client.readText());
        String token = pair.getString("token", "");
        if ("pair_ok".equals(pair.getString("type", "")) && token.length() == 64) {
            passed++;
            System.out.println("[PASS] 安卓客户端配对成功");
        } else {
            failed++;
            System.out.println("[FAIL] 安卓客户端配对");
        }

        client.sendText(Json.obj().put("type", "sms").put("deviceId", "android-test")
                .put("from", "招商银行")
                .put("body", "【招商银行】您的验证码是 773355，请勿告诉他人。")
                .put("ts", System.currentTimeMillis()).put("id", "android-sms-1").toString());
        Json ack = Json.parse(client.readText());
        if ("sms_ack".equals(ack.getString("type", ""))
                && "773355".equals(ack.getString("code", ""))
                && ack.getBool("copied", false)) {
            passed++;
            System.out.println("[PASS] 安卓客户端发送短信并收到回执");
        } else {
            failed++;
            System.out.println("[FAIL] 安卓客户端短信回执: " + ack);
        }

        client.close();
        Thread.sleep(250);

        WsClient again = WsClient.connect("127.0.0.1", 47993, 4000);
        again.sendText(Json.obj().put("type", "auth").put("deviceId", "android-test")
                .put("token", token).toString());
        Json auth = Json.parse(again.readText());
        if ("auth_ok".equals(auth.getString("type", ""))) {
            passed++;
            System.out.println("[PASS] 安卓客户端令牌重连");
        } else {
            failed++;
            System.out.println("[FAIL] 安卓客户端令牌重连: " + auth);
        }
        again.close();
        service.stop();

        System.out.println("通过 " + passed + " 项，失败 " + failed + " 项");
        System.exit(failed == 0 ? 0 : 1);
    }
}
