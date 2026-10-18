package com.smsbridge.pc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 配对、鉴权、收短信、写剪贴板、广播发现的业务核心。 */
public final class BridgeService {
    public interface Listener {
        void onServerStatus(boolean running, String error);

        void onConnectionChanged(boolean online, String deviceName);

        void onMessage(HistoryStore.Entry entry);

        void onDevicesChanged();

        void onLog(String line);
    }

    private static final long PAIR_WINDOW_MS = 10 * 60 * 1000L;
    private static final int MAX_FAILS = 12;

    private final Config config;
    private final HistoryStore history;
    private final ClipboardService clipboard = new ClipboardService();
    private final String pcName;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, FailWindow> failures = new ConcurrentHashMap<>();
    private final Set<String> pendingIds = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object dedupLock = new Object();

    private volatile Listener listener;
    private volatile WsServer server;
    private volatile Discovery discovery;
    private volatile ScheduledExecutorService scheduler;
    private volatile ExecutorService clipboardExecutor;
    private volatile String lastError = "";
    private volatile boolean online;
    private volatile String connectedDevice = "";

    public BridgeService(Config config, HistoryStore history) {
        this.config = config;
        this.history = history;
        this.pcName = detectPcName();
    }

    private static String detectPcName() {
        String name = System.getenv("COMPUTERNAME");
        if (name == null || name.isBlank()) {
            name = System.getProperty("user.name", "我的电脑");
        }
        return name.trim();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public String pcName() {
        return pcName;
    }

    /**
     * 一键配对口令：手机读取剪贴板后可以自动填好 IP、端口和配对码。
     * 格式保持简单，避免依赖 JSON 库或 URL 编码。
     */
    public String pairingPayload() {
        String safeName = pcName.replace("|", " ").replace("=", "-");
        return "SMSBRIDGE-PAIR|v=1|host=" + NetUtil.primaryIp()
                + "|port=" + config.port()
                + "|pin=" + config.pin()
                + "|name=" + safeName;
    }

    public Config config() {
        return config;
    }

    public HistoryStore history() {
        return history;
    }

    public boolean isRunning() {
        return running.get() && server != null && server.isRunning();
    }

    public boolean isOnline() {
        return online;
    }

    public String connectedDevice() {
        return connectedDevice;
    }

    public String lastError() {
        return lastError;
    }

    public synchronized void start() {
        if (running.get()) {
            return;
        }
        lastError = "";
        WsServer ws = new WsServer(config.port(), new ServerEvents());
        try {
            ws.start();
        } catch (IOException e) {
            lastError = "端口 " + config.port() + " 被占用，换一个端口试试";
            notifyServerStatus(false, lastError);
            notifyLog(lastError);
            return;
        }
        server = ws;
        running.set(true);
        clipboardExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "smsbridge-clipboard");
            t.setDaemon(true);
            return t;
        });
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "smsbridge-timer");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(() -> {
            WsServer current = server;
            if (current != null) {
                current.pingAll();
                current.pruneIdle(45_000);
            }
        }, 15, 15, TimeUnit.SECONDS);
        try {
            Discovery d = new Discovery(config, pcName());
            d.start();
            discovery = d;
        } catch (IOException e) {
            notifyLog("局域网自动发现启动失败：" + e.getMessage());
        }
        notifyServerStatus(true, "");
        notifyLog("服务已启动，端口 " + config.port() + "，地址 " + NetUtil.primaryIp());
    }

    public synchronized void stop() {
        if (!running.get()) {
            return;
        }
        running.set(false);
        Discovery d = discovery;
        discovery = null;
        if (d != null) {
            d.close();
        }
        WsServer ws = server;
        server = null;
        if (ws != null) {
            ws.close();
        }
        ScheduledExecutorService s = scheduler;
        scheduler = null;
        if (s != null) {
            s.shutdownNow();
        }
        ExecutorService c = clipboardExecutor;
        clipboardExecutor = null;
        if (c != null) {
            c.shutdown();
        }
        online = false;
        connectedDevice = "";
        notifyConnection(false, "");
        notifyServerStatus(false, "");
    }

    public synchronized void restart() {
        stop();
        start();
    }

    public void setCopyMode(Config.CopyMode mode) {
        config.setCopyMode(mode);
    }

    public String regeneratePin() {
        String pin = config.regeneratePin();
        notifyDevicesChanged();
        return pin;
    }

    public void unpair(String deviceId) {
        config.removeDevice(deviceId);
        WsServer ws = server;
        if (ws != null) {
            for (WsServer.Client client : ws.clients()) {
                if (client.deviceId().equals(deviceId)) {
                    client.close(4001, "unpaired");
                }
            }
        }
        notifyDevicesChanged();
        notifyLog("已移除配对设备");
    }

    public void clearHistory() {
        history.clear();
    }

    public void copyText(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        ExecutorService c = clipboardExecutor;
        if (c != null) {
            c.submit(() -> clipboard.copy(text));
        } else {
            clipboard.copy(text);
        }
    }

    private final class ServerEvents implements WsServer.Listener {
        @Override
        public void onOpen(WsServer.Client client) {
            notifyLog("设备接入：" + client.remoteIp());
        }

        @Override
        public void onMessage(WsServer.Client client, String text) {
            BridgeService.this.onMessage(client, text);
        }

        @Override
        public void onClose(WsServer.Client client) {
            if (client.isAuthed()) {
                recomputeOnline();
            }
            notifyLog("设备断开：" + client.remoteIp());
        }
    }

    private void onMessage(WsServer.Client client, String text) {
        if (text.length() > 64 * 1024) {
            return;
        }
        Json req;
        try {
            req = Json.parse(text);
        } catch (RuntimeException e) {
            reply(client, Json.obj().put("type", "error").put("reason", "bad json"));
            return;
        }
        String type = req.getString("type", "");
        try {
            switch (type) {
                case "pair" -> handlePair(client, req);
                case "auth" -> handleAuth(client, req);
                case "sms" -> handleSms(client, req);
                case "hb" -> reply(client, Json.obj().put("type", "hb_ack")
                        .put("ts", System.currentTimeMillis()));
                case "unpair" -> handleUnpair(client, req);
                default -> reply(client, Json.obj().put("type", "error")
                        .put("reason", "unknown type"));
            }
        } catch (RuntimeException e) {
            reply(client, Json.obj().put("type", "error").put("reason", "bad request"));
            notifyLog("处理消息出错：" + e.getMessage());
        }
    }

    private void handlePair(WsServer.Client client, Json req) {
        String deviceId = trim(req.getString("deviceId", ""), 64);
        String name = trim(req.getString("deviceName", "手机"), 48);
        String pin = req.getString("pin", "");
        if (deviceId.isEmpty()) {
            reply(client, Json.obj().put("type", "pair_err").put("reason", "missing deviceId"));
            return;
        }
        if (!allowAttempt(client.remoteIp())) {
            reply(client, Json.obj().put("type", "pair_err").put("reason", "too many attempts"));
            client.close(4003, "too many attempts");
            return;
        }
        if (!constantEquals(pin, config.pin())) {
            recordFailure(client.remoteIp());
            reply(client, Json.obj().put("type", "pair_err").put("reason", "bad pin"));
            notifyLog("配对码错误：" + client.remoteIp());
            return;
        }
        failures.remove(client.remoteIp());
        String token = newToken();
        config.putDevice(deviceId, name, token, System.currentTimeMillis());
        client.setDevice(deviceId, name);
        client.setAuthed(true);
        reply(client, Json.obj()
                .put("type", "pair_ok")
                .put("token", token)
                .put("pcName", pcName)
                .put("version", Main.VERSION)
                .put("copyMode", config.copyMode().name())
                .put("proto", 1));
        notifyDevicesChanged();
        recomputeOnline();
        notifyLog("配对成功：" + name);
    }

    private void handleAuth(WsServer.Client client, Json req) {
        String deviceId = trim(req.getString("deviceId", ""), 64);
        String token = req.getString("token", "");
        if (!allowAttempt(client.remoteIp())) {
            reply(client, Json.obj().put("type", "auth_err").put("reason", "too many attempts"));
            client.close(4003, "too many attempts");
            return;
        }
        Config.Device device = config.findDevice(deviceId);
        if (device == null || !constantEquals(device.token(), token)) {
            recordFailure(client.remoteIp());
            reply(client, Json.obj().put("type", "auth_err").put("reason", "token"));
            return;
        }
        failures.remove(client.remoteIp());
        client.setDevice(device.id(), device.name());
        client.setAuthed(true);
        config.touchDevice(device, System.currentTimeMillis());
        reply(client, Json.obj()
                .put("type", "auth_ok")
                .put("pcName", pcName)
                .put("version", Main.VERSION)
                .put("copyMode", config.copyMode().name())
                .put("proto", 1));
        notifyDevicesChanged();
        recomputeOnline();
        notifyLog("已自动连接：" + device.name());
    }

    private void handleUnpair(WsServer.Client client, Json req) {
        String deviceId = trim(req.getString("deviceId", client.deviceId()), 64);
        if (deviceId.isEmpty()) {
            return;
        }
        config.removeDevice(deviceId);
        client.setAuthed(false);
        reply(client, Json.obj().put("type", "unpair_ok"));
        notifyDevicesChanged();
        recomputeOnline();
        notifyLog("手机解除了配对");
    }

    private void handleSms(WsServer.Client client, Json req) {
        if (!client.isAuthed()) {
            reply(client, Json.obj().put("type", "sms_err").put("reason", "not paired"));
            return;
        }
        String deviceId = req.getString("deviceId", client.deviceId());
        if (!client.deviceId().equals(deviceId)) {
            reply(client, Json.obj().put("type", "sms_err").put("reason", "device mismatch"));
            return;
        }
        String from = trim(req.getString("from", "未知号码"), 64);
        String body = trim(req.getString("body", ""), 4000);
        long ts = req.getLong("ts", System.currentTimeMillis());
        if (ts < 1_000_000_000_000L) {
            ts = System.currentTimeMillis();
        }
        String id = trim(req.getString("id", ""), 128);
        if (id.isEmpty()) {
            id = UUID.randomUUID().toString();
        }
        if (body.isEmpty()) {
            reply(client, Json.obj().put("type", "sms_ack").put("id", id).put("copied", false));
            return;
        }

        String finalId = id;
        long stamp = ts;
        String code = SmsCode.extract(body);
        boolean full = config.copyMode() == Config.CopyMode.MESSAGE;
        String clip = full || code == null ? body : code;
        synchronized (dedupLock) {
            if (history.contains(finalId) || pendingIds.contains(finalId)) {
                reply(client, Json.obj().put("type", "sms_ack")
                        .put("id", finalId).put("duplicate", true)
                        .put("code", code == null ? "" : code).put("copied", true));
                return;
            }
            pendingIds.add(finalId);
        }

        ExecutorService executor = clipboardExecutor;
        Runnable task = () -> {
            boolean copied = clipboard.copy(clip);
            HistoryStore.Entry entry = new HistoryStore.Entry(
                    finalId, from, body, code, stamp, copied);
            history.add(entry);
            pendingIds.remove(finalId);
            reply(client, Json.obj()
                    .put("type", "sms_ack")
                    .put("id", finalId)
                    .put("code", code == null ? "" : code)
                    .put("copied", copied)
                    .put("mode", full ? "MESSAGE" : "CODE"));
            notifyMessage(entry);
            notifyLog("收到短信 " + from + (code != null ? "，验证码 " + code : "")
                    + (copied ? "，已复制" : "，复制失败"));
        };
        if (executor != null) {
            executor.submit(task);
        } else {
            task.run();
        }
    }

    private void reply(WsServer.Client client, Json response) {
        if (client != null && client.isOpen()) {
            client.sendText(response.toJson());
        }
    }

    private void recomputeOnline() {
        WsServer ws = server;
        boolean nowOnline = false;
        String name = "";
        if (ws != null) {
            for (WsServer.Client client : ws.clients()) {
                if (client.isAuthed() && client.isOpen()) {
                    nowOnline = true;
                    name = client.deviceName().isEmpty() ? "手机" : client.deviceName();
                    break;
                }
            }
        }
        boolean changed = nowOnline != online || !name.equals(connectedDevice);
        online = nowOnline;
        connectedDevice = name;
        if (changed) {
            notifyConnection(nowOnline, name);
        }
    }

    private boolean allowAttempt(String ip) {
        FailWindow window = failures.computeIfAbsent(ip, k -> new FailWindow());
        synchronized (window) {
            long now = System.currentTimeMillis();
            if (now - window.start > PAIR_WINDOW_MS) {
                window.start = now;
                window.count = 0;
            }
            return window.count < MAX_FAILS;
        }
    }

    private void recordFailure(String ip) {
        FailWindow window = failures.computeIfAbsent(ip, k -> new FailWindow());
        synchronized (window) {
            long now = System.currentTimeMillis();
            if (now - window.start > PAIR_WINDOW_MS) {
                window.start = now;
                window.count = 0;
            }
            window.count++;
        }
    }

    private static final class FailWindow {
        long start = System.currentTimeMillis();
        int count;
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : bytes) {
            sb.append(String.format(Locale.ROOT, "%02x", b));
        }
        return sb.toString();
    }

    private static boolean constantEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").trim();
        return cleaned.length() > max ? cleaned.substring(0, max) : cleaned;
    }

    private void notifyServerStatus(boolean ok, String error) {
        Listener l = listener;
        if (l != null) {
            try {
                l.onServerStatus(ok, error);
            } catch (RuntimeException ignored) {
                // 忽略
            }
        }
    }

    private void notifyConnection(boolean nowOnline, String device) {
        Listener l = listener;
        if (l != null) {
            try {
                l.onConnectionChanged(nowOnline, device);
            } catch (RuntimeException ignored) {
                // 忽略
            }
        }
    }

    private void notifyMessage(HistoryStore.Entry entry) {
        Listener l = listener;
        if (l != null) {
            try {
                l.onMessage(entry);
            } catch (RuntimeException ignored) {
                // 忽略
            }
        }
    }

    private void notifyDevicesChanged() {
        Listener l = listener;
        if (l != null) {
            try {
                l.onDevicesChanged();
            } catch (RuntimeException ignored) {
                // 忽略
            }
        }
    }

    private void notifyLog(String line) {
        Listener l = listener;
        if (l != null) {
            try {
                l.onLog(line);
            } catch (RuntimeException ignored) {
                // 忽略
            }
        }
    }

    public List<Config.Device> devices() {
        return new ArrayList<>(config.devices());
    }
}
