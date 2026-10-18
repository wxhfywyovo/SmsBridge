package com.smsbridge.test;

import com.smsbridge.pc.BridgeService;
import com.smsbridge.pc.Config;
import com.smsbridge.pc.HistoryStore;
import com.smsbridge.pc.Json;
import com.smsbridge.pc.SmsCode;
import com.smsbridge.pc.WsClient;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 桌面端端到端自测：配对、鉴权、短信、剪贴板、重启后自动重连。 */
public final class TestHarness {
    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        testJson();
        testSmsCode();
        Path home = Files.createTempDirectory("smsbridge-test-");
        int port = 47991;
        Config config = new Config(home);
        config.setPort(port);
        config.setPin("135790");
        config.setCopyMode(Config.CopyMode.CODE);

        HistoryStore history = new HistoryStore(config.historyFile());
        BridgeService service = new BridgeService(config, history);
        service.setListener(new BridgeService.Listener() {
            @Override
            public void onServerStatus(boolean running, String error) {
                System.out.println("  [event] server=" + running + " " + error);
            }

            @Override
            public void onConnectionChanged(boolean online, String deviceName) {
                System.out.println("  [event] online=" + online + " " + deviceName);
            }

            @Override
            public void onMessage(HistoryStore.Entry entry) {
                System.out.println("  [event] sms " + entry.code() + " copied=" + entry.copied());
            }

            @Override
            public void onDevicesChanged() {
                System.out.println("  [event] devices=" + config.devices().size());
            }

            @Override
            public void onLog(String line) {
                System.out.println("  [log] " + line);
            }
        });
        service.start();
        check("服务启动", service.isRunning());

        WsClient wrong = WsClient.connect("127.0.0.1", port, 4000);
        wrong.sendText(Json.obj().put("type", "pair").put("deviceId", "dev-test")
                .put("deviceName", "测试手机").put("pin", "000000").toJson());
        Json wrongReply = Json.parse(wrong.readText());
        check("错误配对码被拒绝", "pair_err".equals(wrongReply.getString("type", "")));
        wrong.close();

        WsClient client = WsClient.connect("127.0.0.1", port, 4000);
        client.sendText(Json.obj().put("type", "pair").put("deviceId", "dev-test")
                .put("deviceName", "测试手机").put("pin", "135790").toJson());
        Json pair = Json.parse(client.readText());
        check("正确配对码配对成功", "pair_ok".equals(pair.getString("type", "")));
        String token = pair.getString("token", "");
        check("拿到 64 位令牌", token.matches("[0-9a-f]{64}"));
        check("设备已持久化", config.findDevice("dev-test") != null);

        String smsId = "sms-test-1";
        client.sendText(Json.obj().put("type", "sms").put("deviceId", "dev-test")
                .put("from", "中国银行")
                .put("body", "【中国银行】您正在进行登录操作，验证码：482913，5分钟内有效。")
                .put("ts", System.currentTimeMillis()).put("id", smsId).toJson());
        Json ack = Json.parse(client.readText());
        check("短信回执", "sms_ack".equals(ack.getString("type", "")));
        check("验证码识别正确", "482913".equals(ack.getString("code", "")));
        check("短信回执标记已复制", ack.getBool("copied", false));
        check("剪贴板内容是验证码", "482913".equals(readClipboard()));
        check("历史记录已保存", history.recent(10).size() == 1);

        String duplicateReply = sendAndRead(client, Json.obj().put("type", "sms")
                .put("deviceId", "dev-test").put("from", "中国银行")
                .put("body", "【中国银行】验证码：482913").put("id", smsId)
                .put("ts", System.currentTimeMillis()).toJson());
        check("重复短信被去重", Json.parse(duplicateReply).getBool("duplicate", false));

        client.close();
        Thread.sleep(300);

        WsClient reconnect = WsClient.connect("127.0.0.1", port, 4000);
        reconnect.sendText(Json.obj().put("type", "auth").put("deviceId", "dev-test")
                .put("token", token).toJson());
        Json auth = Json.parse(reconnect.readText());
        check("令牌自动重连", "auth_ok".equals(auth.getString("type", "")));
        reconnect.close();

        service.stop();
        check("服务停止", !service.isRunning());
        service.start();
        check("服务重启", service.isRunning());

        WsClient afterRestart = WsClient.connect("127.0.0.1", port, 4000);
        afterRestart.sendText(Json.obj().put("type", "auth").put("deviceId", "dev-test")
                .put("token", token).toJson());
        Json afterAuth = Json.parse(afterRestart.readText());
        check("电脑重启后令牌仍可自动连接", "auth_ok".equals(afterAuth.getString("type", "")));

        afterRestart.sendText(Json.obj().put("type", "sms").put("deviceId", "dev-test")
                .put("from", "GitHub")
                .put("body", "[GitHub] Your verification code is 927461. It expires in 10 minutes.")
                .put("ts", System.currentTimeMillis()).put("id", "sms-test-2").toJson());
        Json second = Json.parse(afterRestart.readText());
        check("英文短信验证码识别", "927461".equals(second.getString("code", "")));
        check("第二次剪贴板", "927461".equals(readClipboard()));

        afterRestart.close();
        service.stop();
        System.out.println();
        System.out.println("通过 " + passed + " 项，失败 " + failed + " 项");
        cleanup(home);
        System.exit(failed == 0 ? 0 : 1);
    }

    private static String sendAndRead(WsClient client, String message) throws Exception {
        client.sendText(message);
        return client.readText();
    }

    private static String readClipboard() {
        try {
            return (String) Toolkit.getDefaultToolkit().getSystemClipboard()
                    .getData(DataFlavor.stringFlavor);
        } catch (Exception e) {
            return "";
        }
    }

    private static void testJson() {
        Json j = Json.obj().put("type", "sms").put("n", 42).put("ok", true)
                .put("text", "引号\" 反斜杠\\ 换行\n 中文");
        Json parsed = Json.parse(j.toJson());
        check("JSON 往返 type", "sms".equals(parsed.getString("type", "")));
        check("JSON 往返 数字", parsed.getLong("n", 0) == 42);
        check("JSON 往返 布尔", parsed.getBool("ok", false));
        check("JSON 往返 转义", j.getString("text", "").equals(parsed.getString("text", "")));
    }

    private static void testSmsCode() {
        check("识别 验证码：", "482913".equals(
                SmsCode.extract("【中国银行】您正在进行登录操作，验证码：482913，5分钟内有效。")));
        check("识别带空格验证码", "123456".equals(
                SmsCode.extract("您的验证码是 123 456，请勿泄露")));
        check("识别英文验证码", "927461".equals(
                SmsCode.extract("[GitHub] Your verification code is 927461. It expires in 10 minutes.")));
        check("不误抓手机号", SmsCode.extract("联系电话 13800138000") == null);
        check("不误抓订单号", SmsCode.extract("订单 20240501123456 已发货") == null);
        check("识别 4 位验证码", "8848".equals(SmsCode.extract("验证码：8848，请及时输入")));
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

    private static void cleanup(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // 忽略
                }
            });
        } catch (Exception ignored) {
            // 忽略
        }
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        latch.await(3, TimeUnit.SECONDS);
    }
}
