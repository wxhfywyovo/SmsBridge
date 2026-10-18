package com.smsbridge.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 常驻前台服务：维持和电脑的 WebSocket 连接。
 * 断线后按 1/2/3/5/8/12/20/30 秒退避重连，网络变化时立即重连；
 * 配对令牌保存在本地，所以电脑重启后也能自动连回去。
 */
public final class SyncService extends Service {
    public static final String ACTION_START = "com.smsbridge.mobile.START";
    public static final String ACTION_STOP = "com.smsbridge.mobile.STOP";
    public static final String ACTION_PAIR = "com.smsbridge.mobile.PAIR";
    public static final String ACTION_SMS = "com.smsbridge.mobile.SMS";
    public static final String ACTION_FLUSH = "com.smsbridge.mobile.FLUSH";

    public static final String EXTRA_HOST = "host";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_PIN = "pin";
    public static final String EXTRA_FROM = "from";
    public static final String EXTRA_BODY = "body";
    public static final String EXTRA_TS = "ts";
    public static final String EXTRA_ID = "id";

    private static final String CHANNEL_ID = "smsbridge-sync-v2";
    private static final String OLD_CHANNEL_ID = "smsbridge-sync";
    private static final int NOTIFICATION_ID = 1001;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    public enum State { IDLE, CONNECTING, ONLINE, NEED_PAIR, ERROR }

    public static final class Status {
        public final State state;
        public final String title;
        public final String detail;
        public final String pcName;
        public final boolean running;

        Status(State state, String title, String detail, String pcName, boolean running) {
            this.state = state;
            this.title = title;
            this.detail = detail;
            this.pcName = pcName;
            this.running = running;
        }
    }

    public interface Listener {
        void onStatus(Status status);

        void onRecent(Prefs.Msg msg);

        void onLog(String line);
    }

    private static volatile Status lastStatus =
            new Status(State.IDLE, "还没有连接电脑", "打开配对，输入电脑上的 6 位配对码", "电脑", false);

    public static Status lastStatus() {
        return lastStatus;
    }

    public static void addListener(Listener listener) {
        LISTENERS.add(listener);
        MAIN.post(() -> listener.onStatus(lastStatus));
    }

    /** 短信（通过通知读取获得）先保存到本地时，把这条记录推给正在看的 App 界面。 */
    public static void publishLocalSms(Prefs.Msg msg) {
        MAIN.post(() -> {
            for (Listener listener : LISTENERS) {
                listener.onRecent(msg);
            }
        });
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    private Prefs prefs;
    private volatile boolean running;
    private volatile WsClient client;
    private volatile boolean forcePair;
    private volatile String pendingHost;
    private volatile String pendingPin;
    private volatile boolean needUser;
    private volatile boolean connectionReady;
    private volatile int connectFailures;
    private Thread worker;
    private final Object wakeLock = new Object();
    private final Object sendLock = new Object();
    private NotificationManager notificationManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private PowerManager.WakeLock cpuWakeLock;
    private WifiManager.WifiLock wifiLock;
    private boolean foregroundStarted;
    private ExecutorService sendExecutor;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = Prefs.get(this);
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createChannel();
        startForegroundCompat("正在启动同步…", "保持和电脑的连接");
        running = true;
        acquireKeepAlive();
        sendExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "smsbridge-send");
            t.setDaemon(true);
            return t;
        });
        registerNetworkCallback();
        worker = new Thread(this::connectionLoop, "smsbridge-connection");
        worker.setDaemon(true);
        worker.start();
        log("服务启动");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            prefs.setEnabled(false);
            shutdown();
            return START_NOT_STICKY;
        }
        if (ACTION_PAIR.equals(action)) {
            String host = intent.getStringExtra(EXTRA_HOST);
            int port = intent.getIntExtra(EXTRA_PORT, 47823);
            String pin = intent.getStringExtra(EXTRA_PIN);
            if (host != null && !host.isEmpty()) {
                prefs.setServer(host.trim(), port);
            }
            if (pin != null && pin.matches("\\d{4,8}")) {
                prefs.setPin(pin);
            }
            prefs.setToken("");
            prefs.setEnabled(true);
            forcePair = true;
            pendingHost = host == null ? null : host.trim();
            pendingPin = pin;
            needUser = false;
            connectFailures = 0;
            log("收到手动配对请求");
            wake();
            return START_STICKY;
        }
        if (ACTION_FLUSH.equals(action)) {
            int count = prefs.queue().size();
            if (connectionReady) {
                flushQueueAsync();
            } else {
                wake();
            }
            log("手动发送队列（" + count + " 条）");
            return START_STICKY;
        }
        if (ACTION_SMS.equals(action)) {
            String body = intent.getStringExtra(EXTRA_BODY);
            if (body != null && !body.isEmpty()) {
                String id = intent.getStringExtra(EXTRA_ID);
                if (id == null || id.isEmpty()) {
                    id = Long.toHexString(System.currentTimeMillis());
                }
                Prefs.Msg msg = new Prefs.Msg(id,
                        intent.getStringExtra(EXTRA_FROM),
                        body,
                        SmsCode.extract(body),
                        intent.getLongExtra(EXTRA_TS, System.currentTimeMillis()),
                        false);
                prefs.enqueue(msg);
                SmsLogger.logSms(this, "service-intent", msg,
                        "enabled=" + prefs.enabled());
                log("收到短信，已放入队列");
                if (!prefs.enabled()) {
                    log("同步开关未开启，短信留在手机队列");
                    setStatus(State.IDLE, "同步已关闭", "打开后台常驻同步后会自动补发");
                    return START_STICKY;
                }
                setStatus(connectionReady ? State.ONLINE : State.CONNECTING,
                        "收到新短信", "正在同步到电脑…");
                flushQueueAsync();
            }
            return START_STICKY;
        }
        prefs.setEnabled(true);
        needUser = false;
        wake();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        running = false;
        closeClient();
        synchronized (wakeLock) {
            wakeLock.notifyAll();
        }
        unregisterNetworkCallback();
        if (sendExecutor != null) {
            sendExecutor.shutdownNow();
            sendExecutor = null;
        }
        if (worker != null) {
            worker.interrupt();
        }
        releaseKeepAlive();
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        }
        publishStopped();
        log("服务停止");
        super.onDestroy();
    }

    private void shutdown() {
        running = false;
        closeClient();
        synchronized (wakeLock) {
            wakeLock.notifyAll();
        }
        releaseKeepAlive();
        stopForeground(STOP_FOREGROUND_REMOVE);
        publishStopped();
        stopSelf();
    }

    private void publishStopped() {
        Status status = new Status(State.IDLE, "同步已关闭", "打开后台常驻同步即可连接",
                prefs == null ? "电脑" : prefs.pcName(), false);
        lastStatus = status;
        MAIN.post(() -> {
            for (Listener listener : LISTENERS) {
                listener.onStatus(status);
            }
        });
    }

    // ------------------------------------------------------------ connection

    private void connectionLoop() {
        while (running) {
            try {
                if (!prefs.enabled()) {
                    waitFor(1200);
                    continue;
                }
                needUser = false;
                String host = prefs.host();
                if (forcePair && pendingHost != null && !pendingHost.isEmpty()) {
                    host = pendingHost;
                }
                if (host.isEmpty()) {
                    setStatus(State.CONNECTING, "正在局域网里找电脑…", "确保电脑端已打开，且在同一 Wi-Fi");
                    Discovery.Host found = discoverHost();
                    if (found == null) {
                        connectFailures = 0;
                        setStatus(State.NEED_PAIR, "没有找到电脑", "检查同一 Wi-Fi、电脑防火墙和配对码");
                        waitFor(5000);
                        continue;
                    }
                    host = found.ip;
                    prefs.setServer(host, found.port);
                }
                setStatus(State.CONNECTING, "正在连接电脑…", host + ":" + prefs.port());
                WsClient ws = WsClient.connect(host, prefs.port(), 6500);
                client = ws;
                connectFailures = 0;
                if (!authenticate(ws)) {
                    connectionReady = false;
                    closeClient();
                    waitFor(needUser ? 300_000 : 2_000);
                    continue;
                }
                connectionReady = true;
                setStatus(State.ONLINE, "已连接 · " + prefs.pcName(), "收到的短信会自动复制到电脑剪贴板");
                log("连接成功：" + prefs.pcName());
                flushQueue(ws);
                readLoop(ws);
            } catch (Exception e) {
                if (!running) {
                    break;
                }
                connectFailures++;
                if (connectFailures >= 2) {
                    // IP 可能变了，下次从头开始，用 UDP 广播重新找电脑
                    prefs.setServer("", prefs.port());
                }
                setStatus(State.CONNECTING, "连接断开，正在自动重连…", shortError(e));
                log("连接失败：" + shortError(e));
            } finally {
                connectionReady = false;
                closeClient();
                client = null;
            }
            if (!running) {
                break;
            }
            waitFor(needUser ? 300_000 : backoff(connectFailures));
        }
    }

    private boolean authenticate(WsClient ws) throws IOException {
        String token = prefs.token();
        if (!forcePair && !token.isEmpty()) {
            ws.sendText(Json.obj()
                    .put("type", "auth")
                    .put("deviceId", prefs.deviceId())
                    .put("token", token)
                    .toString());
            Json reply = readReply(ws);
            String type = reply.getString("type", "");
            if ("auth_ok".equals(type)) {
                prefs.setPcName(reply.getString("pcName", "电脑"));
                return true;
            }
            if ("auth_err".equals(type)) {
                prefs.setToken("");
                log("旧令牌失效，尝试用配对码重新配对");
            } else if ("error".equals(type)) {
                throw new IOException("电脑返回错误");
            }
        }

        String pin = forcePair && pendingPin != null && !pendingPin.isEmpty()
                ? pendingPin : prefs.pin();
        if (pin == null || !pin.matches("\\d{4,8}")) {
            needUser = true;
            setStatus(State.NEED_PAIR, "还差一步", "在 App 里输入电脑屏幕上的 6 位配对码");
            return false;
        }
        ws.sendText(Json.obj()
                .put("type", "pair")
                .put("deviceId", prefs.deviceId())
                .put("deviceName", prefs.deviceName())
                .put("pin", pin)
                .toString());
        Json reply = readReply(ws);
        String type = reply.getString("type", "");
        if ("pair_ok".equals(type)) {
            prefs.setToken(reply.getString("token", ""));
            prefs.setPcName(reply.getString("pcName", "电脑"));
            prefs.setPin(pin);
            forcePair = false;
            pendingPin = null;
            pendingHost = null;
            return true;
        }
        if ("pair_err".equals(type)) {
            String reason = reply.getString("reason", "");
            forcePair = false;
            needUser = true;
            prefs.setToken("");
            setStatus(State.NEED_PAIR,
                    "too many attempts".equals(reason) ? "尝试太频繁" : "配对码不对",
                    "看看电脑屏幕上的 6 位数字，再试一次");
            log("配对失败：" + reason);
            return false;
        }
        throw new IOException("配对响应异常");
    }

    private Json readReply(WsClient ws) throws IOException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            String text = ws.readText();
            if (text == null) {
                throw new IOException("连接已关闭");
            }
            Json json = Json.parse(text);
            String type = json.getString("type", "");
            if (type.endsWith("_ok") || type.endsWith("_err") || "error".equals(type)) {
                return json;
            }
        }
        throw new SocketTimeoutException("等待电脑响应超时");
    }

    private void flushQueue(WsClient ws) throws IOException {
        synchronized (sendLock) {
            List<Prefs.Msg> queue = new ArrayList<>(prefs.queue());
            for (Prefs.Msg msg : queue) {
                if (!running || client != ws) {
                    return;
                }
                sendSms(ws, msg);
            }
        }
    }

    /**
     * 收到新短信时连接线程通常正阻塞在 readLoop 里，wake() 叫不醒它。
     * 必须主动在后台线程把队列发出去，否则短信会一直躺在队列里等下次重连。
     */
    private void flushQueueAsync() {
        WsClient ws = client;
        if (!connectionReady || ws == null || !ws.isOpen()) {
            wake();
            return;
        }
        ExecutorService executor = sendExecutor;
        if (executor == null || executor.isShutdown()) {
            wake();
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    flushQueue(ws);
                    wake();
                } catch (IOException e) {
                    log("发送短信失败：" + e.getClass().getSimpleName());
                    wake();
                }
            });
        } catch (Exception e) {
            wake();
        }
    }

    private void sendSms(WsClient ws, Prefs.Msg msg) throws IOException {
        ws.sendText(Json.obj()
                .put("type", "sms")
                .put("deviceId", prefs.deviceId())
                .put("from", msg.from == null ? "" : msg.from)
                .put("body", msg.body == null ? "" : msg.body)
                .put("ts", msg.ts)
                .put("id", msg.id)
                .toString());
        log("发送短信 id=" + msg.id + " from=" + msg.from
                + " len=" + (msg.body == null ? 0 : msg.body.length()));
    }
    private void readLoop(WsClient ws) throws IOException {
        while (running && client == ws) {
            String text = ws.readText();
            if (text == null) {
                return;
            }
            Json json;
            try {
                json = Json.parse(text);
            } catch (RuntimeException e) {
                continue;
            }
            String type = json.getString("type", "");
            if ("sms_ack".equals(type)) {
                handleAck(json);
            } else if ("sms_err".equals(type)) {
                log("电脑拒收短信：" + json.getString("reason", ""));
            } else if ("error".equals(type)) {
                log("电脑返回错误：" + json.getString("reason", ""));
            }
        }
    }

    private void handleAck(Json json) {
        String id = json.getString("id", "");
        if (id.isEmpty()) {
            return;
        }
        Prefs.Msg original = null;
        for (Prefs.Msg msg : prefs.queue()) {
            if (msg.id.equals(id)) {
                original = msg;
                break;
            }
        }
        prefs.removeQueued(id);
        String code = json.getString("code", "");
        boolean copied = json.getBool("copied", true);
        log("收到回执 id=" + id + " code=" + code + " copied=" + copied);
        if (original != null) {
            Prefs.Msg recent = new Prefs.Msg(original.id, original.from, original.body,
                    code, original.ts, copied);
            prefs.addRecent(recent);
            publishRecent(recent);
            String summary = code.isEmpty() ? "短信已同步" : "验证码 " + code + " 已复制";
            setStatus(State.ONLINE, "已连接 · " + prefs.pcName(), summary);
            log("同步成功：" + summary);
        }
    }

    private Discovery.Host discoverHost() {
        List<Discovery.Host> hosts = Discovery.discover(2800);
        if (hosts.isEmpty()) {
            return null;
        }
        String saved = prefs.pcName();
        for (Discovery.Host host : hosts) {
            if (host.name.equalsIgnoreCase(saved)) {
                return host;
            }
        }
        return hosts.get(0);
    }

    private long backoff(int failures) {
        long[] delays = {800, 1500, 2500, 4000, 6500, 10_000, 15_000, 20_000, 30_000};
        int index = Math.max(0, Math.min(failures - 1, delays.length - 1));
        return delays[index];
    }

    private void waitFor(long millis) {
        synchronized (wakeLock) {
            try {
                wakeLock.wait(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void wake() {
        synchronized (wakeLock) {
            wakeLock.notifyAll();
        }
    }

    private void closeClient() {
        WsClient ws = client;
        if (ws != null) {
            ws.close();
        }
    }

    private static String shortError(Exception e) {
        if (e instanceof ConnectException) {
            return "电脑没开机，或端口不对";
        }
        if (e instanceof SocketTimeoutException) {
            return "连接超时，检查防火墙";
        }
        if (e instanceof UnknownHostException) {
            return "找不到电脑地址";
        }
        String message = e.getMessage();
        if (message == null || message.isEmpty()) {
            return "网络暂时不可用";
        }
        return message.length() > 60 ? message.substring(0, 60) : message;
    }

    // ------------------------------------------------------------ network

    private void registerNetworkCallback() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) {
                return;
            }
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    log("网络可用，尝试重连");
                    closeClient();
                    wake();
                }

                @Override
                public void onLost(Network network) {
                    log("网络断开，等待恢复");
                    closeClient();
                }
            };
            cm.registerDefaultNetworkCallback(networkCallback);
        } catch (Exception ignored) {
            // 某些系统不允许注册，退避重连仍然有效
        }
    }

    private void unregisterNetworkCallback() {
        if (networkCallback == null) {
            return;
        }
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm != null) {
                cm.unregisterNetworkCallback(networkCallback);
            }
        } catch (Exception ignored) {
            // 忽略
        }
        networkCallback = null;
    }

    /**
     * 锁屏保活的关键：
     * 1. PARTIAL_WAKE_LOCK 让 CPU 在锁屏/Doze 时不睡；
     * 2. WIFI_MODE_FULL_HIGH_PERF 让 Wi-Fi 射频保持高性能。
     * 前台服务本身只保证进程优先级，不保证 CPU/网络不被休眠。
     */
    @SuppressWarnings("deprecation")
    private void acquireKeepAlive() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                cpuWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmsBridge:sync");
                cpuWakeLock.setReferenceCounted(false);
                cpuWakeLock.acquire();
            }
        } catch (Exception e) {
            log("申请 CPU 保活锁失败：" + e.getClass().getSimpleName());
        }
        try {
            WifiManager wm = (WifiManager) getApplicationContext()
                    .getSystemService(WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                        "SmsBridge:wifi");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Exception e) {
            log("申请 Wi-Fi 保活锁失败：" + e.getClass().getSimpleName());
        }
        log("已申请锁屏保活锁（CPU / Wi-Fi）");
    }

    private void releaseKeepAlive() {
        try {
            if (cpuWakeLock != null && cpuWakeLock.isHeld()) {
                cpuWakeLock.release();
            }
        } catch (Exception ignored) {
            // 忽略
        }
        cpuWakeLock = null;
        try {
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
            }
        } catch (Exception ignored) {
            // 忽略
        }
        wifiLock = null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (prefs != null && prefs.enabled()) {
            log("任务列表被划掉，前台服务继续保活");
            // 部分 ROM 划掉任务时会顺手杀进程；先尝试把服务重新拉起来。
            try {
                startService(new Intent(this, SyncService.class)
                        .setAction(ACTION_START));
            } catch (Exception e) {
                SmsLogger.event(this, "任务移除后重启服务失败："
                        + e.getClass().getSimpleName());
            }
        }
        super.onTaskRemoved(rootIntent);
    }

    // ------------------------------------------------------------ notification

    private void createChannel() {
        try {
            if (notificationManager.getNotificationChannel(OLD_CHANNEL_ID) != null) {
                notificationManager.deleteNotificationChannel(OLD_CHANNEL_ID);
            }
        } catch (Exception ignored) {
            // 忽略
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notification_channel_desc));
        channel.setShowBadge(false);
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        notificationManager.createNotificationChannel(channel);
    }

    private Notification buildNotification(String title, String text) {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_sms)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pending)
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setLocalOnly(true)
                .setPriority(Notification.PRIORITY_MIN)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private void startForegroundCompat(String title, String text) {
        Notification notification = buildNotification(title, text);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        foregroundStarted = true;
    }

    private void updateNotification(Status status) {
        if (!foregroundStarted || notificationManager == null) {
            return;
        }
        try {
            notificationManager.notify(NOTIFICATION_ID,
                    buildNotification(status.title, status.detail));
        } catch (Exception ignored) {
            // 忽略通知失败
        }
    }

    private void setStatus(State state, String title, String detail) {
        Status status = new Status(state, title, detail,
                prefs == null ? "电脑" : prefs.pcName(), true);
        lastStatus = status;
        MAIN.post(() -> {
            for (Listener listener : LISTENERS) {
                listener.onStatus(status);
            }
        });
        updateNotification(status);
    }

    private void publishRecent(Prefs.Msg msg) {
        MAIN.post(() -> {
            for (Listener listener : LISTENERS) {
                listener.onRecent(msg);
            }
        });
    }

    private void log(String line) {
        if (prefs != null) {
            prefs.addEvent(line);
        }
        SmsLogger.event(this, line);
        MAIN.post(() -> {
            for (Listener listener : LISTENERS) {
                listener.onLog(line);
            }
        });
    }
}
