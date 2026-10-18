package com.smsbridge.mobile;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 手机端只做轻量识别，用于在本地列表里先显示验证码；最终以电脑端识别为准。 */
public final class SmsCode {
    private static final Pattern KEY = Pattern.compile(
            "(验证码|校验码|动态码|确认码|激活码|安全码|提取码|口令|"
                    + "認証番号|確認コード|認証コード|code|verification code|passcode|otp)"
                    + "[^0-9]{0,24}(\\d{4,8})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern FALLBACK = Pattern.compile("(?<!\\d)(\\d{4,8})(?!\\d)");

    private SmsCode() {
    }

    public static String extract(String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        String normalized = body.replace('０', '0').replace('１', '1').replace('２', '2')
                .replace('３', '3').replace('４', '4').replace('５', '5')
                .replace('６', '6').replace('７', '7').replace('８', '8')
                .replace('９', '9');
        // WhatsApp 验证码常见 123-456 格式
        for (int i = 0; i < 3; i++) {
            String next = normalized.replaceAll("(?<=\\d)[ \\-‑](?=\\d)", "");
            if (next.equals(normalized)) {
                break;
            }
            normalized = next;
        }
        Matcher matcher = KEY.matcher(normalized);
        if (matcher.find()) {
            return matcher.group(2);
        }
        // 没有关键词时只在很短的通知里兜底，避免把订单号/手机号当验证码
        if (normalized.length() <= 40) {
            Matcher fallback = FALLBACK.matcher(normalized);
            if (fallback.find()) {
                String digits = fallback.group(1);
                if (!digits.startsWith("19") && !digits.startsWith("20")) {
                    return digits;
                }
            }
        }
        return null;
    }
}
