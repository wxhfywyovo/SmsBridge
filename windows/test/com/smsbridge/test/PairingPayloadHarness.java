package com.smsbridge.test;

import com.smsbridge.mobile.PairingInfo;
import com.smsbridge.mobile.SmsCode;
import com.smsbridge.pc.BridgeService;
import com.smsbridge.pc.Config;
import com.smsbridge.pc.HistoryStore;
import java.nio.file.Files;
import java.nio.file.Path;

/** 验证“电脑一键复制配对信息 → 手机解析”的格式，以及手机端本地验证码识别。 */
public final class PairingPayloadHarness {
    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("smsbridge-pairing-");
        Config config = new Config(home);
        config.setPort(47997);
        config.setPin("135790");
        HistoryStore history = new HistoryStore(config.historyFile());
        BridgeService service = new BridgeService(config, history);

        String payload = service.pairingPayload();
        System.out.println("payload=" + payload);
        PairingInfo info = PairingInfo.parse(payload);
        check("解析一键配对信息", info != null);
        if (info != null) {
            check("host 正确", info.hasHost());
            check("port 正确", info.port == 47997);
            check("pin 正确", "135790".equals(info.pin));
        }

        PairingInfo bare = PairingInfo.parse("  246810\n");
        check("允许手动剪贴板只有配对码", bare != null && "246810".equals(bare.pin)
                && !bare.hasHost());
        check("普通文字不会被误判", PairingInfo.parse("这是一段普通文字") == null);
        check("不完整口令不会误判",
                PairingInfo.parse("SMSBRIDGE-PAIR|v=1|host=|port=47823|pin=123456") == null);

        check("手机识别中文验证码", "482913".equals(
                SmsCode.extract("【中国银行】验证码：482913，5分钟内有效。")));
        check("手机识别英文验证码", "927461".equals(
                SmsCode.extract("Your verification code is 927461.")));
        check("手机不误抓手机号", SmsCode.extract("联系电话 13800138000") == null);
        check("手机识别短数字", "8848".equals(SmsCode.extract("验证码 8848")));

        System.out.println("通过 " + passed + " 项，失败 " + failed + " 项");
        System.exit(failed == 0 ? 0 : 1);
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
