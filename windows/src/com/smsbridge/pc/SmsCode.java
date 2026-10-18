package com.smsbridge.pc;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 从短信正文中提取验证码。识别不了时返回 null，由调用方决定复制整条短信。 */
public final class SmsCode {
    private static final Pattern KEYWORDS = Pattern.compile(
            "验证码|校验码|动态码|确认码|激活码|安全码|登录码|短信码|提取码|口令|"
                    + "\\bcode\\b|\\botp\\b|\\bpasscode\\b|\\bpin\\b|verification code",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern CANDIDATE = Pattern.compile("(?<!\\d)(\\d{4,8})(?!\\d)");

    private static final Pattern DATE_LIKE = Pattern.compile("(19|20)\\d{2}[-/.年]\\d{1,2}[-/.月]\\d{1,2}");

    private SmsCode() {
    }

    public static String extract(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        String text = normalize(body);

        int[] keywordStarts = keywordPositions(text);
        boolean hasKeyword = keywordStarts.length > 0;

        Matcher m = CANDIDATE.matcher(text);
        String best = null;
        int bestScore = 0;
        while (m.find()) {
            String digits = m.group(1);
            int start = m.start(1);
            int end = m.end(1);

            int bestDistance = Integer.MAX_VALUE;
            for (int keywordStart : keywordStarts) {
                bestDistance = Math.min(bestDistance, Math.abs(start - keywordStart));
            }

            int score = 0;
            if (hasKeyword) {
                if (bestDistance <= 36) {
                    score += 100 - bestDistance * 2;
                } else if (bestDistance <= 80) {
                    score += 30;
                } else {
                    score -= 20;
                }
            } else {
                score -= 60;
            }

            score += switch (digits.length()) {
                case 6 -> 24;
                case 4 -> 14;
                case 5 -> 12;
                case 7 -> 8;
                case 8 -> 6;
                default -> 0;
            };

            if (digits.length() == 4 && (digits.startsWith("19") || digits.startsWith("20"))) {
                score -= 34;
            }
            if (digits.length() == 8 && (digits.startsWith("19") || digits.startsWith("20"))) {
                score -= 10;
            }
            if (DATE_LIKE.matcher(text).find()) {
                score -= 4;
            }
            // 紧邻“元/块/折/日/年/月/号/分/秒”等量词的数字通常不是验证码
            String before = start >= 2 ? text.substring(start - 2, start) : "";
            String after = end + 2 <= text.length() ? text.substring(end, end + 2) : "";
            if (after.matches("^[元块折日年月号分秒个次位条].*")
                    || before.matches(".*[元块折日年月号分秒个次位条]$")) {
                score -= 25;
            }

            if (score > bestScore) {
                bestScore = score;
                best = digits;
            }
        }

        return bestScore >= 50 ? best : null;
    }

    private static String normalize(String body) {
        StringBuilder sb = new StringBuilder(body.length());
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c >= '０' && c <= '９') {
                sb.append((char) ('0' + (c - '０')));
            } else if (c == '－' || c == '—' || c == '–') {
                sb.append('-');
            } else if (c == '：') {
                sb.append(':');
            } else {
                sb.append(c);
            }
        }
        String text = sb.toString();
        // 把 “123 456”“123-456” 这类被空格/短横拆开的验证码重新拼起来
        for (int round = 0; round < 4; round++) {
            String next = text.replaceAll("(?<=\\d)[ \\t\\u00a0]+(?=\\d)", "")
                    .replaceAll("(?<=\\d)[-‑](?=\\d)", "");
            if (next.equals(text)) {
                break;
            }
            text = next;
        }
        return text;
    }

    private static int[] keywordPositions(String text) {
        Matcher m = KEYWORDS.matcher(text);
        int[] positions = new int[8];
        int count = 0;
        while (m.find() && count < positions.length) {
            positions[count++] = m.start();
        }
        int[] result = new int[count];
        System.arraycopy(positions, 0, result, 0, count);
        return result;
    }
}
