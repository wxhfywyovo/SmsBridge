package com.smsbridge.pc;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

/** 桌面端持久化配置：端口、配对码、偏好、已配对设备令牌。 */
public final class Config {
    public enum CopyMode { CODE, MESSAGE }

    public record Device(String id, String name, String token, long lastSeen) {
        public Device touch(long ts) {
            return new Device(id, name, token, ts);
        }
    }

    public static final int DEFAULT_PORT = 47823;
    public static final int DISCOVERY_PORT = 47824;

    private final Path dir;
    private final Path file;
    private final Properties props = new Properties();
    private final SecureRandom random = new SecureRandom();
    private volatile String saveError = "";

    public Config(Path dir) {
        this.dir = dir;
        this.file = dir.resolve("config.properties");
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
            // 目录不可写时仍然允许内存运行，保存时会报错并在界面提示
        }
        load();
        boolean dirty = false;
        if (string("pin", "").length() != 6) {
            props.setProperty("pin", newPin());
            dirty = true;
        }
        if (port() < 1024 || port() > 65535) {
            props.setProperty("port", Integer.toString(DEFAULT_PORT));
            dirty = true;
        }
        if (!props.containsKey("copyMode")) {
            props.setProperty("copyMode", CopyMode.CODE.name());
            dirty = true;
        }
        if (dirty) {
            save();
        }
    }

    public Path dir() {
        return dir;
    }

    public Path historyFile() {
        return dir.resolve("history.jsonl");
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(r);
        } catch (IOException | IllegalArgumentException ignored) {
            // 配置损坏则使用默认值
        }
    }

    public synchronized void save() {
        Path tmp = file.resolveSibling("config.properties.tmp");
        try {
            Files.createDirectories(dir);
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                props.store(w, "SmsBridge desktop config");
                try {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException e) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            saveError = "";
        } catch (IOException e) {
            saveError = "配置无法写入 " + dir + "：" + e.getMessage();
            System.err.println("SmsBridge " + saveError);
        }
    }

    public String saveError() {
        return saveError;
    }

    private String string(String key, String def) {
        String v = props.getProperty(key);
        return v == null ? def : v;
    }

    public int port() {
        try {
            return Integer.parseInt(string("port", Integer.toString(DEFAULT_PORT)).trim());
        } catch (NumberFormatException e) {
            return DEFAULT_PORT;
        }
    }

    public synchronized void setPort(int port) {
        props.setProperty("port", Integer.toString(port));
        save();
    }

    public String pin() {
        String p = string("pin", "");
        return p.length() == 6 ? p : newPin();
    }

    public synchronized String regeneratePin() {
        String pin = newPin();
        props.setProperty("pin", pin);
        save();
        return pin;
    }

    public synchronized void setPin(String pin) {
        if (pin != null && pin.matches("\\d{6}")) {
            props.setProperty("pin", pin);
            save();
        }
    }

    private String newPin() {
        return String.format("%06d", 100000 + random.nextInt(900000));
    }

    public CopyMode copyMode() {
        try {
            return CopyMode.valueOf(string("copyMode", "CODE"));
        } catch (IllegalArgumentException e) {
            return CopyMode.CODE;
        }
    }

    public synchronized void setCopyMode(CopyMode mode) {
        props.setProperty("copyMode", mode.name());
        save();
    }

    public boolean autoStart() {
        return Boolean.parseBoolean(string("autoStart", "false"));
    }

    public synchronized void setAutoStart(boolean value) {
        props.setProperty("autoStart", Boolean.toString(value));
        save();
    }

    public boolean closeToTray() {
        return Boolean.parseBoolean(string("closeToTray", "true"));
    }

    public synchronized void setCloseToTray(boolean value) {
        props.setProperty("closeToTray", Boolean.toString(value));
        save();
    }

    public synchronized List<Device> devices() {
        List<Device> result = new ArrayList<>();
        for (String key : props.stringPropertyNames()) {
            if (!key.startsWith("device.") || !key.endsWith(".token")) {
                continue;
            }
            String id = key.substring("device.".length(), key.length() - ".token".length());
            String token = props.getProperty(key, "");
            if (id.isEmpty() || token.isEmpty()) {
                continue;
            }
            result.add(new Device(id,
                    string("device." + id + ".name", "手机"),
                    token,
                    parseLong(string("device." + id + ".lastSeen", "0"))));
        }
        result.sort(Comparator.comparingLong(Device::lastSeen).reversed());
        return result;
    }

    public synchronized Device findDevice(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        String token = props.getProperty("device." + id + ".token");
        if (token == null || token.isEmpty()) {
            return null;
        }
        return new Device(id,
                string("device." + id + ".name", "手机"),
                token,
                parseLong(string("device." + id + ".lastSeen", "0")));
    }

    public synchronized void putDevice(String id, String name, String token, long lastSeen) {
        props.setProperty("device." + id + ".token", token);
        props.setProperty("device." + id + ".name", name == null ? "手机" : name);
        props.setProperty("device." + id + ".lastSeen", Long.toString(lastSeen));
        save();
    }

    public synchronized void touchDevice(Device device, long ts) {
        props.setProperty("device." + device.id() + ".lastSeen", Long.toString(ts));
        save();
    }

    public synchronized void removeDevice(String id) {
        props.remove("device." + id + ".token");
        props.remove("device." + id + ".name");
        props.remove("device." + id + ".lastSeen");
        save();
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
