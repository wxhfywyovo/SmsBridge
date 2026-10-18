package com.smsbridge.mobile;

import java.util.LinkedHashMap;
import java.util.Map;

/** 解析电脑复制到剪贴板的一键配对信息。 */
public final class PairingInfo {
    public static final String PREFIX = "SMSBRIDGE-PAIR|";

    public final String host;
    public final int port;
    public final String pin;
    public final String name;

    private PairingInfo(String host, int port, String pin, String name) {
        this.host = host;
        this.port = port;
        this.pin = pin;
        this.name = name;
    }

    public boolean hasHost() {
        return host != null && !host.isEmpty();
    }

    public static PairingInfo parse(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.startsWith(PREFIX)) {
            Map<String, String> values = new LinkedHashMap<>();
            String[] parts = text.split("\\|");
            for (int i = 1; i < parts.length; i++) {
                int eq = parts[i].indexOf('=');
                if (eq > 0) {
                    values.put(parts[i].substring(0, eq).trim().toLowerCase(),
                            parts[i].substring(eq + 1).trim());
                }
            }
            String host = values.getOrDefault("host", "");
            String pin = values.getOrDefault("pin", "");
            String name = values.getOrDefault("name", "电脑");
            int port = 47823;
            try {
                port = Integer.parseInt(values.getOrDefault("port", "47823"));
            } catch (NumberFormatException ignored) {
                // 使用默认端口
            }
            if (host.isEmpty() || !pin.matches("\\d{4,8}") || port < 1024 || port > 65535) {
                return null;
            }
            return new PairingInfo(host, port, pin, name);
        }
        // 手动点“从剪贴板配对”时，允许只复制一个配对码，沿用当前/发现的 IP。
        if (text.matches("\\d{4,8}")) {
            return new PairingInfo("", 47823, text, "");
        }
        return null;
    }
}
